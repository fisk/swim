package org.fisk.swim.plugins.email;

import java.util.List;

record EmailTagRulesConfig(List<EmailTagRuleConfig> rules) {
    EmailTagRulesConfig {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    static EmailTagRulesConfig empty() {
        return new EmailTagRulesConfig(List.of());
    }
}
