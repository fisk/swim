package org.fisk.swim.plugins.email;

import java.util.List;

record EmailTagRulesConfig(List<EmailTagRuleConfig> rules) {
    EmailTagRulesConfig {
        rules = rules == null
                ? List.of()
                : rules.stream().filter(java.util.Objects::nonNull).toList();
    }

    static EmailTagRulesConfig empty() {
        return new EmailTagRulesConfig(List.of());
    }
}
