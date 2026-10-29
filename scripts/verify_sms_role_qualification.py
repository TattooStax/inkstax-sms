#!/usr/bin/env python3
"""Verify a build carries the default-SMS role qualification surface.

Android offers the SMS role only to an app whose manifest declares
four components with exact intent shapes (the ROLE_SMS contract in
the platform's role documentation). A build that loses any one of
them still installs and runs, but the system SMS picker silently
never offers it, so the loss is invisible until someone stands at
a phone trying to select the app as the default SMS app. This
check reads the built artifacts so the loss is caught at build
time instead:

  verify_sms_role_qualification.py <merged-manifest.xml> [apk]

The merged manifest is checked structurally: each requirement must
be met by one enabled, exported component of the right kind,
carrying the right permission, action, category, scheme, and MIME
type. When an APK is also given, its binary manifest (read through
aapt2) must contain every required literal, confirming the surface
survived packaging into the artifact that would be installed.

Exit code 0 means every requirement is present; 1 lists each
requirement that is missing.
"""

import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"

SENDTO = "android.intent.action.SENDTO"
RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"
SMS_DELIVER = "android.provider.Telephony.SMS_DELIVER"
WAP_PUSH_DELIVER = "android.provider.Telephony.WAP_PUSH_DELIVER"
DEFAULT_CATEGORY = "android.intent.category.DEFAULT"
SEND_RESPOND_PERMISSION = "android.permission.SEND_RESPOND_VIA_MESSAGE"
BROADCAST_SMS_PERMISSION = "android.permission.BROADCAST_SMS"
BROADCAST_WAP_PUSH_PERMISSION = "android.permission.BROADCAST_WAP_PUSH"
SMSTO_SCHEME = "smsto"
MMS_MIME_TYPE = "application/vnd.wap.mms-message"


def intent_filters(component):
    """Yield (actions, categories, schemes, mime_types) per intent-filter."""
    for intent_filter in component.findall("intent-filter"):
        actions = {
            el.get(ANDROID + "name") for el in intent_filter.findall("action")
        }
        categories = {
            el.get(ANDROID + "name") for el in intent_filter.findall("category")
        }
        schemes = set()
        mime_types = set()
        for data in intent_filter.findall("data"):
            scheme = data.get(ANDROID + "scheme")
            if scheme:
                schemes.add(scheme)
            mime_type = data.get(ANDROID + "mimeType")
            if mime_type:
                mime_types.add(mime_type)
        yield actions, categories, schemes, mime_types


def usable(component):
    """A component the platform cannot invoke cannot qualify its app."""
    return (
        component.get(ANDROID + "enabled") != "false"
        and component.get(ANDROID + "exported") != "false"
    )


def any_filter(component, predicate):
    return any(predicate(f) for f in intent_filters(component))


def check_manifest(path):
    """Return the list of unmet requirement descriptions."""
    application = ET.parse(path).getroot().find("application")
    if application is None:
        return ["manifest has no <application> element"]

    def components(kind):
        return [el for el in application.findall(kind) if usable(el)]

    unmet = []

    # The user must be able to compose a message to a number through
    # the platform's standard send-to intent.
    if not any(
        any_filter(
            activity,
            lambda f: SENDTO in f[0]
            and DEFAULT_CATEGORY in f[1]
            and SMSTO_SCHEME in f[2],
        )
        for activity in components("activity")
    ) and not any(
        any_filter(
            alias,
            lambda f: SENDTO in f[0]
            and DEFAULT_CATEGORY in f[1]
            and SMSTO_SCHEME in f[2],
        )
        for alias in components("activity-alias")
    ):
        unmet.append(
            "no activity handles SENDTO with category DEFAULT and scheme smsto"
        )

    # The phone app must be able to hand the app a respond-via-message
    # request during a call, through a service only the system can
    # invoke for that purpose.
    if not any(
        service.get(ANDROID + "permission") == SEND_RESPOND_PERMISSION
        and any_filter(
            service,
            lambda f: RESPOND_VIA_MESSAGE in f[0]
            and DEFAULT_CATEGORY in f[1]
            and SMSTO_SCHEME in f[2],
        )
        for service in components("service")
    ):
        unmet.append(
            "no service gated by SEND_RESPOND_VIA_MESSAGE handles "
            "RESPOND_VIA_MESSAGE with category DEFAULT and scheme smsto"
        )

    # Inbound SMS is delivered only to the default app, through a
    # receiver only the system can trigger.
    if not any(
        receiver.get(ANDROID + "permission") == BROADCAST_SMS_PERMISSION
        and any_filter(receiver, lambda f: SMS_DELIVER in f[0])
        for receiver in components("receiver")
    ):
        unmet.append(
            "no receiver gated by BROADCAST_SMS handles SMS_DELIVER"
        )

    # Inbound MMS arrives as a WAP push, through a receiver only the
    # system can trigger, typed as an MMS message.
    if not any(
        receiver.get(ANDROID + "permission") == BROADCAST_WAP_PUSH_PERMISSION
        and any_filter(
            receiver,
            lambda f: WAP_PUSH_DELIVER in f[0] and MMS_MIME_TYPE in f[3],
        )
        for receiver in components("receiver")
    ):
        unmet.append(
            "no receiver gated by BROADCAST_WAP_PUSH handles "
            "WAP_PUSH_DELIVER with mime type application/vnd.wap.mms-message"
        )

    return unmet


REQUIRED_APK_LITERALS = [
    SENDTO,
    SMSTO_SCHEME,
    RESPOND_VIA_MESSAGE,
    SEND_RESPOND_PERMISSION,
    SMS_DELIVER,
    BROADCAST_SMS_PERMISSION,
    WAP_PUSH_DELIVER,
    BROADCAST_WAP_PUSH_PERMISSION,
    MMS_MIME_TYPE,
]


def find_aapt2():
    candidate = os.environ.get("AAPT2")
    if candidate:
        return candidate
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    roots = [sdk] if sdk else []
    roots.append("/opt/android-sdk")
    for root in roots:
        if not root:
            continue
        matches = sorted(glob.glob(os.path.join(root, "build-tools", "*", "aapt2")))
        if matches:
            return matches[-1]
    return "aapt2"


def check_apk(path):
    """Presence-level check: every required literal survives packaging."""
    aapt2 = find_aapt2()
    result = subprocess.run(
        [aapt2, "dump", "xmltree", path, "--file", "AndroidManifest.xml"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return [f"aapt2 could not read the APK manifest: {result.stderr.strip()}"]
    return [
        f"APK manifest is missing {literal}"
        for literal in REQUIRED_APK_LITERALS
        if literal not in result.stdout
    ]


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__)
        return 2
    unmet = check_manifest(argv[1])
    if len(argv) == 3:
        unmet += check_apk(argv[2])
    if unmet:
        for item in unmet:
            print(f"FAIL: {item}")
        return 1
    print("PASS: the build carries the full default-SMS role qualification surface")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
