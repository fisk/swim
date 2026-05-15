package org.fisk.swim.plugins.email;

import java.util.LinkedHashMap;
import java.util.Map;

record OAuthTokenCache(Map<String, OAuthTokenRecord> accounts) {
    OAuthTokenCache {
        accounts = accounts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(accounts);
    }

    static OAuthTokenCache empty() {
        return new OAuthTokenCache(new LinkedHashMap<>());
    }
}
