# SWIM Architecture

SWIM is a Java terminal editor built around a thin launcher, a background session server, a reloadable editor app process, and JPMS plugin modules. The important architectural rule is that core infrastructure defines extension points, while feature plugins depend inward on those extension points. `swim-core` and `swim-launcher` must not depend on concrete plugin modules.

## Process Model

Normal startup goes through three layers:

```text
terminal
  |
  v
bin/swim
  |
  v
swim-launcher client JVM
  |
  v
swim-session server JVM
  |
  v
per-session SWIM app JVM
```

`bin/swim` is generated next to the custom runtime image and uses the image's embedded Java. The public entry point is `org.fisk.swim.launcher.Main`.

When `Main` is run normally, it starts `SwimSessionClient`. The client ensures a background `swim-session` server is running, connects over a Unix-domain socket, enters raw terminal mode, and relays terminal input/output to the attached app session. It also relays resize events explicitly.

The session server owns live editor sessions. It handles attach, detach, switch, list, kill, resize, size, and ping requests. Each editor session is a separate app JVM with stdio pipes connected to the server. The app command is supplied by the client at attach time, so the server does not derive future app commands from its own version of the code. That is the version boundary that lets existing sessions keep running while rebuilt sessions start from newer artifacts.

The app process runs `Main --swim-app ...`. That path loads the core app and plugins, then waits until the app requests exit.

JVM sizing is intentionally split:

- Launcher client and session server JVMs use `-XX:+UseZGC -Xmx128M`.
- App JVMs use `-XX:+UseZGC -Xmx4G -XX:SoftMaxHeapSize=1G --sun-misc-unsafe-memory-access=allow`.
- On Java 26+, app JVMs also add `--enable-final-field-mutation=ALL-UNNAMED --illegal-final-field-mutation=allow`.

## Module Map

The Maven reactor is the architectural map:

- `swim-launcher`: stable host/plugin API, command-line launcher, session client, plugin discovery/loading, rebuild/reload host, and runtime image installer.
- `swim-session`: background session server and the public live-session socket API used by the launcher, editor, and Nemo.
- `swim-core`: editor app implementation, terminal UI, modal editing, buffers, commands, layouts, built-in workspaces, Nemo, and shared integration registries.
- `swim-lsp`: shared asynchronous LSP/editor interoperability used by language plugins.
- `swim-java-lsp`: Java LSP plugin using the bundled Oracle/NetBeans language-server payload.
- `swim-clangd-lsp`: C/C++ LSP plugin using `clangd`.
- `swim-java-debug` and `swim-cpp-debug`: debugger provider plugins.
- `swim-tree-view`: tree-view panel plugin.
- `swim-email`: mail workspace plugin.
- `swim-git`: Git and GitHub review workspace plugin.
- `swim-slack`: Slack workspace plugin.

Installed runtime artifacts are copied to:

- `bin/`: the public `swim` launcher intended for `PATH`.
- `image/`: the generated runtime image and embedded Java.
- `plugins/`: core and plugin jars.
- `plugins/runtime-libs/`: runtime dependencies used by the app/plugin layers.
- `share/man/`: the generated `swim(1)` man page paired with `bin/`; its editor-help section is rendered from the same `HelpDocument` used by `:help`.
- `deps/oracle.oracle-java/`: bundled Java language-server payload.

## Launch And Plugin Loading

`swim-launcher` exports the stable extension API under `org.fisk.swim.api`. The core app provides `SwimApp`; plugins provide `SwimPlugin`.

Core and plugin code are loaded through JPMS layers:

1. `Main.createCoreLayer` resolves `org.fisk.swim.core` plus its runtime libraries.
2. `PluginRegistry` finds the `SwimApp` service from the core layer.
3. `PluginRegistry` scans installed plugin jars, creates a plugin layer that reads the boot and core layers, and discovers `SwimPlugin` providers through `ServiceLoader`.
4. Bindings are sorted by load order and id.
5. Plugin `preload` runs first for all plugins.
6. Plugins whose `loadOnStartup()` returns true are loaded immediately; others remain available for lazy loading.

Preload is for lightweight metadata that must exist before the full plugin runtime is loaded, such as help chapters and key bindings. Plugin documentation belongs here: core renders the help tree, but plugin-owned topics are registered by the plugin modules that implement those features. Full `load` is for runtime state, external clients, panels, background workers, and service registrations.

Every plugin must clean up in `close()`. If a plugin starts threads, owns clients, registers tools, or registers editor resources, unload must terminate those resources. Shared registries unregister plugin-owned resources during unload, but plugin-owned threads and external handles are still the plugin's responsibility.

## Plugin Boundary

The compile-time dependency shape is:

```text
feature plugins  --->  swim-lsp  --->  swim-core  --->  swim-launcher/api  --->  swim-session
       |                  |               ^
       +------------------+---------------+
```

The arrow means "requires". Plugins depend on `swim-launcher` for the API and on `swim-core` when they need core extension points or UI model types. LSP plugins also depend on `swim-lsp`, which depends inward on core for editor/UI types and on the launcher API for shared plugin help registration. `swim-core` must not depend on `swim-lsp` or concrete plugin implementation packages. It may expose plugin-facing registries such as language, debugger, mail, Slack, Nemo-tool, help, and panel hooks.

Plugins can integrate through these main APIs:

- `SwimPlugin`: plugin identity, load order, startup policy, preload/load/close lifecycle.
- `SwimPluginPreloadContext`: preload-time registration of help chapters and key bindings.
- `SwimPluginContext`: load-time access to the host, initial paths, current path, help registration, and Nemo-tool registration.
- `SwimHost`: host operations such as reload, rebuild, plugin loading, exit, and panel registration.
- `SwimPanel`: plugin-rendered panels that core can host in workspaces or side panels.

Key bindings are registered declaratively through `SwimPluginKeyBinding`. Normal mode builds responders from the registry and reports conflicts where a plugin binding can never trigger because an existing binding or prefix wins.

## Editor App Runtime

The core app implementation is `SwimAppImpl`. Startup does the following:

1. Configure logging.
2. Bind `SwimRuntime` to the host supplied by the launcher.
3. Create the singleton `Window`.
4. Draw the initial UI.
5. Start the event thread.
6. Start the terminal input thread.

The editor uses a single UI/event model:

- `IOThread` reads terminal input from Lanterna and enqueues events.
- `EventThread` serializes editor events and calls back into `Window.update`.
- `Window` owns global editor state: workspaces, tabs, frames, active view, command view, mode line, tab bar, overlays, and layout.
- `View` subclasses render concrete surfaces such as buffers, command menus, popups, plugin panels, shell workspaces, help, diagnostics, and directory browsing.
- `Mode` implementations, especially `NormalMode`, `InputMode`, and visual modes, translate key events into buffer/view/window actions.
- `Buffer` and `BufferContext` own text, undo state, file identity, language mode, and editor-local buffer state.

Rendering is explicit. Resizes and other global UI invalidations should dirty the full UI and force a fresh layout/redraw. Reload and rebuild preserve the alternate screen and session snapshot where possible, but normal app shutdown must restore terminal state, including cursor shape and full-screen mode.

## Workspaces, Tabs, And Frames

`Window` models the editor as workspaces. A workspace can be an editor layout, a plugin panel, help, shell, directory browser, or another app-like view. Editor workspaces contain tabs and frames. Closing follows Vim-like semantics: close the current frame first, then the current tab, and only exit the app when the last tab is gone.

Plugin panels are hosted through `PluginPanelView`, which adapts `SwimPanel.renderRich`, `handleInput`, and key-binding hints into the core view system. Plugins can open panels without `Window` knowing concrete plugin classes.

## Reload And Rebuild

`:reload` and `:rebuild` are host requests, not in-place class redefinition.

- `SwimRuntime.reload()` asks the launcher host to unload the current app/plugin layer and load a new one from already-built artifacts.
- `SwimRuntime.rebuildAndReload()` asks the launcher host to run `mvn -q -DskipTests package`, then reload.
- Before reload, the current app checkpoints session layout and state.
- During reload, core closes the current app but keeps terminal state active so the replacement app can take over without leaving the terminal.
- If reload fails, `PluginRegistry` attempts to restore the previous loaded app/plugins.

Because plugin classes are loaded from JPMS layers, stale static state is avoided by unloading plugin instances and dropping the layer. This only works if plugins close their own threads and external resources.

## LSP Architecture

Language support is plugin-backed. Core owns the editor-facing `LanguageMode` contract and shared UI surfaces for diagnostics, completion popups, location popups, code actions, and project diagnostic navigation.

`LanguagePluginRegistry` maps file extensions to `LanguageMode` factories. Language plugins register those mappings during preload. When a `Buffer` opens or changes path, core resolves the language mode through that registry. If no plugin matches, core uses a plain no-op language mode.

`swim-lsp` holds shared asynchronous plumbing so language plugins do not duplicate editor/LSP coordination:

- `AsyncLspRequestQueue`: single-thread scheduled request queue with explicit shutdown/join behavior.
- `LspDocumentChangeBatcher`: batches and flushes document changes to the language server.
- `AsyncSemanticTokenHighlighter`: snapshots document text, records local insert/delete mutations while a semantic-token request is running, applies the mutation log to returned highlights, and schedules a fresh snapshot when needed.
- `AsyncCompletionCoordinator`: snapshots completion requests, cancels stale generations, and applies completion results back on the editor thread.
- `LspFeatureSupport`: shared client-capability advertisement, async LSP feature requests, stale-result rejection, workspace edits, and colored popup/prompt UI integration for standard LSP actions.

Language-specific plugins should focus on starting and adapting their language server, mapping server capabilities to `LanguageMode`, and registering language-specific commands. Shared editor behavior such as async coloring, async completion, diagnostics patching, workspace edit application, and standard LSP popup UI belongs in `swim-lsp`.

## Nemo

Nemo lives in core because it integrates deeply with buffers, project context, commands, approvals, and editor-control sandboxing. It supports OpenAI-compatible requests through langchain4j or the Responses API path, MCP servers, worker sessions, web search, plugin tools, and host-approved editor control.

The editor-control boundary is intentionally strict. Nemo-visible actions are opt-in and must make action-level allow/block decisions. Private integrations such as Mail must not expose confidential content through Nemo snapshots, tool output, logs, prompts, or tests.

Plugins can register Nemo tools through `SwimPluginContext.registerNemoTool`. Those tools run plugin code and are exposed only when the current Nemo configuration permits plugin tools.

## Testing Expectations

There are two important test layers:

- `mvn test` runs the normal unit and non-Failsafe test suites.
- `mvn verify` runs Failsafe integration tests, including tmux-driven installed-launcher tests.

Startup and reload issues often only appear after `mvn package` regenerates `bin/`, `image/`, and `plugins/`. For launcher, plugin loading, terminal mode, or runtime-image changes, use tmux integration coverage that starts `bin/swim` with a fresh home. A classpath-only unit test is not enough to catch JPMS layer, plugin jar, generated launcher, or terminal startup regressions.
