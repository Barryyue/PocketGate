package com.networkcontrol.android.kotlin.core.model

enum class Direction(val code: Int) {
    Outbound(0),
    Inbound(1);

    companion object {
        fun fromCode(code: Int): Direction = entries.firstOrNull { it.code == code } ?: Outbound
    }
}

enum class L4Protocol(val number: Int) {
    Icmp(1),
    Tcp(6),
    Udp(17),
    IcmpV6(58),
    Other(255);

    companion object {
        fun fromNumber(number: Int): L4Protocol = entries.firstOrNull { it.number == number } ?: Other

        fun fromCode(code: Int): L4Protocol = entries.firstOrNull { it.number == code } ?: Other
    }
}

enum class VerdictAction(val code: Int) {
    Allow(0),
    Deny(1);

    companion object {
        fun fromCode(code: Int): VerdictAction = entries.firstOrNull { it.code == code } ?: Deny
    }
}

enum class FilterMode(val code: Int) {
    Ask(0),
    Whitelist(1),
    Blacklist(2),
    BlockAll(3),
    Disabled(4);

    companion object {
        fun fromCode(code: Int): FilterMode = entries.firstOrNull { it.code == code } ?: Ask
    }
}

enum class RuleLifetime(val code: Int) {
    Once(0),
    UntilProcessExit(1),
    Timed(2),
    UntilRestart(3),
    Permanent(4);

    companion object {
        fun fromCode(code: Int): RuleLifetime = entries.firstOrNull { it.code == code } ?: Permanent
    }
}

enum class RuleSource(val code: Int) {
    User(0),
    Prompt(1),
    Builtin(2),
    Subscription(3),
    Import(4);

    companion object {
        fun fromCode(code: Int): RuleSource = entries.firstOrNull { it.code == code } ?: User
    }
}

enum class RuleEndReason(val code: Int) {
    Deleted(0),
    Expired(1),
    ProcessExited(2),
    Replaced(3);

    companion object {
        fun fromCode(code: Int): RuleEndReason = entries.firstOrNull { it.code == code } ?: Deleted
    }
}

enum class VerdictReason(val code: Int) {
    Rule(0),
    Mode(1),
    Prompt(2),
    PromptTimeout(3),
    Builtin(4),
    NoUserSession(5),
    DoNotDisturb(6),
    FailSafe(7),
    Cancelled(8);

    companion object {
        fun fromCode(code: Int): VerdictReason = entries.firstOrNull { it.code == code } ?: FailSafe
    }
}

enum class NetworkZone(val code: Int) {
    Loopback(0),
    LocalNetwork(1),
    Internet(2);

    companion object {
        fun fromCode(code: Int): NetworkZone = entries.firstOrNull { it.code == code } ?: Internet
    }
}

enum class NetworkKind(val code: Int) {
    None(0),
    Wired(1),
    Wifi(2),
    Cellular(3),
    Vpn(4),
    Other(5);

    companion object {
        fun fromCode(code: Int): NetworkKind = entries.firstOrNull { it.code == code } ?: Other
    }
}

enum class FlowOrigin(val code: Int) {
    Pending(0),
    BlockedEvent(1);

    companion object {
        fun fromCode(code: Int): FlowOrigin = entries.firstOrNull { it.code == code } ?: BlockedEvent
    }
}

enum class AppRoutingDecision(val code: Int) {
    Bypass(0),
    Block(1),
    BlockAndAsk(2);

    companion object {
        fun fromCode(code: Int): AppRoutingDecision = entries.firstOrNull { it.code == code } ?: Block
    }
}

enum class AppRoutingReason(val code: Int) {
    Self(0),
    SystemUid(1),
    Rule(2),
    Mode(3);

    companion object {
        fun fromCode(code: Int): AppRoutingReason = entries.firstOrNull { it.code == code } ?: Mode
    }
}

enum class RuleSkipReason(val code: Int) {
    UnsupportedConditions(0),
    Inbound(1);

    companion object {
        fun fromCode(code: Int): RuleSkipReason = entries.firstOrNull { it.code == code } ?: UnsupportedConditions
    }
}

enum class PromptScope(val code: Int) {
    AnyConnection(0),
    Domain(1),
    Address(2),
    AddressAndPort(3),
    Port(4);

    companion object {
        fun fromCode(code: Int): PromptScope = entries.firstOrNull { it.code == code } ?: AnyConnection
    }
}

enum class PromptEventKind(val code: Int) {
    Added(0),
    Updated(1),
    Removed(2);

    companion object {
        fun fromCode(code: Int): PromptEventKind = entries.firstOrNull { it.code == code } ?: Updated
    }
}

enum class AppDecision(val code: Int) {
    Unset(0),
    Allow(1),
    Deny(2);

    companion object {
        fun fromCode(code: Int): AppDecision = entries.firstOrNull { it.code == code } ?: Unset
    }
}

enum class ProtectionStatus(val code: Int) {
    Off(0),
    NeedsConsent(1),
    Starting(2),
    On(3),
    Revoked(4),
    Failed(5);

    companion object {
        fun fromCode(code: Int): ProtectionStatus = entries.firstOrNull { it.code == code } ?: Failed
    }
}

enum class RuleImportMode(val code: Int) {
    Merge(0),
    Replace(1);

    companion object {
        fun fromCode(code: Int): RuleImportMode = entries.firstOrNull { it.code == code } ?: Merge
    }
}

enum class AndroidRuleIneffectiveReason(val code: Int) {
    UnsupportedConditions(0),
    Inbound(1),
    NotAndroidApp(2);

    companion object {
        fun fromCode(code: Int): AndroidRuleIneffectiveReason = entries.firstOrNull { it.code == code } ?: UnsupportedConditions
    }
}
