package org.fisk.swim.plugins.cppdebug;

import java.util.List;
import java.util.Map;

sealed interface GdbMiValue permits GdbMiValue.Const, GdbMiValue.Tuple, GdbMiValue.Array {
    record Const(String value) implements GdbMiValue {
    }

    record Tuple(Map<String, GdbMiValue> fields) implements GdbMiValue {
        GdbMiValue get(String key) {
            return fields.get(key);
        }
    }

    record Array(List<GdbMiValue> items) implements GdbMiValue {
    }
}
