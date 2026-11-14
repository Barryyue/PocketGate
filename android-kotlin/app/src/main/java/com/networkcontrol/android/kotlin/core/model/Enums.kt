package com.networkcontrol.android.kotlin.core.model

enum class Direction(val code: Int) {
    Outbound(0),
    Inbound(1);

    companion object {
        fun fromCode(code: Int): Direction = entries.first { it.code == code }
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

        fun fromCode(code: Int): L4Protocol = entries.first { it.number == code }
    }
}

enum class VerdictAction(val code: Int) {
    Allow(0),
    Deny(1);

    companion object {
        fun fromCode(code: Int): VerdictAction = entries.first { it.code == code }
    }
}

enum class FilterMode(val code: Int) {
    Ask(0),
    Whitelist(1),
    Blacklist(2),
    BlockAll(3),
    Disabled(4);

    companion object {
        fun fromCode(code: Int): FilterMode = entries.first { it.code == code }
    }
}

enum class RuleLifetime(val code: Int) {
    Once(0),
    UntilProcessExit(1),
    Timed(2),
    UntilRestart(3),
    Permanent(4);

    companion object {
        fun fromCode(code: Int): RuleLifetime = entries.first { it.code == code }
    }
}

enum class RuleSource(val code: Int) {
    User(0),
    Prompt(1),
    Builtin(2),
    Subscription(3),
    Import(4);

    companion object {
        fun fromCode(code: Int): RuleSource = entries.first { it.code == code }
    }
}

enum class RuleEndReason(val code: Int) {
    Deleted(0),
    Expired(1),
    ProcessExited(2),
    Replaced(3);

    companion object {
        fun fromCode(code: Int): RuleEndReason = entries.first { it.code == code }
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
        fun fromCode(code: Int): VerdictReason = entries.first { it.code == code }
    }
}

enum class NetworkZone(val code: Int) {
    Loopback(0),
    LocalNetwork(1),
    Internet(2);

    companion object {
        fun fromCode(code: Int): NetworkZone = entries.first { it.code == code }
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
        fun fromCode(code: Int): NetworkKind = entries.first { it.code == code }
    }
}

enum class FlowOrigin(val code: Int) {
    Pending(0),
    BlockedEvent(1);

    companion object {
        fun fromCode(code: Int): FlowOrigin = entries.first { it.code == code }
    }
}

enum class AppRoutingDecision(val code: Int) {
    Bypass(0),
    Block(1),
    BlockAndAsk(2);

    companion object {
        fun fromCode(code: Int): AppRoutingDecision = entries.first { it.code == code }
    }
}

enum class AppRoutingReason(val code: Int) {
    Self(0),
    SystemUid(1),
    Rule(2),
    Mode(3);

    companion object {
        fun fromCode(code: Int): AppRoutingReason = entries.first { it.code == code }
    }
}

enum class RuleSkipReason(val code: Int) {
    UnsupportedConditions(0),
    Inbound(1);

    companion object {
        fun fromCode(code: Int): RuleSkipReason = entries.first { it.code == code }
    }
}

enum class PromptScope(val code: Int) {
    AnyConnection(0),
    Domain(1),
    Address(2),
    AddressAndPort(3),
    Port(4);

    companion object {
        fun fromCode(code: Int): PromptScope = entries.first { it.code == code }
    }
}

enum class PromptEventKind(val code: Int) {
    Added(0),
    Updated(1),
    Removed(2);

    companion object {
        fun fromCode(code: Int): PromptEventKind = entries.first { it.code == code }
    }
}

enum class AppDecision(val code: Int) {
    Unset(0),
    Allow(1),
    Deny(2);

    companion object {
        fun fromCode(code: Int): AppDecision = entries.first { it.code == code }
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
        fun fromCode(code: Int): ProtectionStatus = entries.first { it.code == code }
    }
}

enum class RuleImportMode(val code: Int) {
    Merge(0),
    Replace(1);

    companion object {
        fun fromCode(code: Int): RuleImportMode = entries.first { it.code == code }
    }
}

enum class AndroidRuleIneffectiveReason(val code: Int) {
    UnsupportedConditions(0),
    Inbound(1),
    NotAndroidApp(2);

    companion object {
        fun fromCode(code: Int): AndroidRuleIneffectiveReason = entries.first { it.code == code }
    }
}
