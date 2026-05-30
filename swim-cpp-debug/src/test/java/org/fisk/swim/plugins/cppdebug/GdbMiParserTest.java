package org.fisk.swim.plugins.cppdebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.fisk.swim.debug.DebugSourceLocation;
import org.junit.jupiter.api.Test;

class GdbMiParserTest {
    @Test
    void parsesResultAndAsyncRecords() {
        String chunk = """
                ^done,threads=[{id="1",target-id="Thread 0x1",state="stopped",frame={level="0",func="main",fullname="/tmp/main.cpp",line="4"}}],current-thread-id="1"
                *stopped,reason="breakpoint-hit",frame={level="0",func="main",fullname="/tmp/main.cpp",line="4"},thread-id="1"
                """;

        var parsed = GdbMiParser.parseChunk(chunk);
        assertEquals(1, parsed.resultRecords().size());
        assertEquals(1, parsed.asyncRecords().size());
        assertEquals("done", ((GdbMiValue.Const) parsed.resultRecords().getFirst().get("_record")).value());
        assertEquals("stopped", ((GdbMiValue.Const) parsed.asyncRecords().getFirst().get("_record")).value());
    }

    @Test
    void cppSessionParsesThreadsFramesAndVariablesLikeGdbMi() {
        String threadInfo = """
                ^done,threads=[{id="1",target-id="Thread 0x1",state="stopped",frame={level="0",func="main",fullname="/tmp/main.cpp",line="4"}},{id="2",target-id="Thread 0x2",state="running"}],current-thread-id="1"
                """;
        var parsedThreads = GdbMiParser.parseChunk(threadInfo);
        var threads = new java.util.ArrayList<org.fisk.swim.debug.DebugThreadInfo>();
        var threadArray = (GdbMiValue.Array) parsedThreads.resultRecords().getFirst().get("threads");
        for (var item : threadArray.items()) {
            var tuple = (GdbMiValue.Tuple) item;
            threads.add(new org.fisk.swim.debug.DebugThreadInfo(
                    ((GdbMiValue.Const) tuple.get("id")).value(),
                    ((GdbMiValue.Const) tuple.get("target-id")).value(),
                    "stopped".equals(((GdbMiValue.Const) tuple.get("state")).value())));
        }
        assertEquals(2, threads.size());
        assertTrue(threads.getFirst().suspended());

        String frameInfo = """
                ^done,stack=[frame={level="0",func="main",fullname="/tmp/main.cpp",line="4"},frame={level="1",func="start",fullname="/tmp/start.cpp",line="1"}]
                """;
        var parsedFrames = GdbMiParser.parseChunk(frameInfo);
        var stack = (GdbMiValue.Array) parsedFrames.resultRecords().getFirst().get("stack");
        var firstFrame = (GdbMiValue.Tuple) ((GdbMiValue.Tuple) stack.items().getFirst()).get("frame");
        DebugSourceLocation location = new DebugSourceLocation(Path.of(((GdbMiValue.Const) firstFrame.get("fullname")).value()),
                Integer.parseInt(((GdbMiValue.Const) firstFrame.get("line")).value()), 1,
                ((GdbMiValue.Const) firstFrame.get("func")).value());
        assertEquals(4, location.line());

        String variables = """
                ^done,variables=[{name="value",type="int",value="2"},{name="name",type="const char *",value="\\"demo\\""}]
                """;
        var parsedVariables = GdbMiParser.parseChunk(variables);
        var vars = (GdbMiValue.Array) parsedVariables.resultRecords().getFirst().get("variables");
        assertEquals(2, vars.items().size());
    }
}
