# Kai Build

**Last verified:** 2026-11-17

Kai Build is an **Android-only** coding environment inside the Kai app. It is a separate *surface* from chat — no shared conversation store, no sandbox tools, no provider/settings coupling — but it is not necessarily a separate *Linux*: when the chat sandbox also runs Debian, both work in the same install.

It is a **single screen with three states** — set up Linux, pick a project, work in that project's terminal.

## Concepts

### Separate product surface

Kai Build is opened from the **empty chat state**, from an outlined button under "Start Interactive UI". The button is drawn in the phosphor green of the terminal it opens and carries a terminal glyph, so what it leads to is readable before it is pressed. It is shown on Android only.

The surface then takes over the whole screen: no navigation destination, no second launcher icon, and no place in the back stack. A back arrow in its top bar returns to the chat — or, inside a project, to the project list — and the mode is not persisted across app restarts, so a cold start lands in chat. It survives rotation.

The system back gesture is **not** intercepted: it leaves the app rather than stepping out of Kai Build, which is why the top bar carries its own back arrow.

### Which Linux it runs in

Kai Build is always Debian: its coding agents are vendor scripts that expect glibc and apt.

Debian has exactly one install on the device, normally under app-private `kai-build/`. Kai Build works in it unconditionally, and never moves. What varies is whether the chat [Linux sandbox](sandbox.md) is pointed at the same install:

- **Shell integration is Debian** (the default) — both use the *same* install. Whichever surface installs it first, the other finds it already there: set Linux up in Settings and Kai Build opens straight on the project list; install from Kai Build's setup screen and the sandbox reports itself ready. Uninstalling from either removes it for both, and both dialogs say so.
- **Shell integration is Alpine** — that runs in a separate Alpine under app-private `linux-sandbox/`, and the two coexist.

The chat sandbox can be switched between the two at any time from Settings, and neither install is touched when it happens — so a Kai Build project terminal is never disturbed by that choice. The sandbox can also copy a home directory across when switching, but never the coding agents: their binaries are built for one distribution's libc, and Kai Build installs them per environment. (A Debian installed by an earlier build that put it under `linux-sandbox/` is found and used where it is, rather than downloaded again.)

Either way `/root` (agent binaries and config) is on the rootfs, and an external-files folder is bind-mounted only to `/root/projects`, so project code stays USB/MTP reachable without putting executables on storage that Android often mounts noexec. That projects folder is the same one in every arrangement, so projects are unaffected by the sandbox's distribution.

### Set up Linux

First run shows a single setup card: a beta notice, a short explanation, a checkbox per coding agent, and one **Install Debian** button. The setup title and description mark the feature as beta. Install downloads a Debian Bookworm rootfs from the Linux Containers image index (architecture-matched: arm64, armhf, amd64, i386), extracts it, configures DNS, then runs `apt-get update` and installs the essentials every project needs — bash, ca-certificates, curl, wget, git, nano, less, unzip, python3, tar and coreutils. Any agents ticked beforehand are installed straight after, so a fresh system arrives ready to use. The first agent in the list is ticked to begin with.

Progress replaces the install button — download percent, extract, configure, base packages, per-agent — with Cancel next to it, and a failure message stays on screen. A Debian install is only considered complete once its base packages are in, so an interrupted install can never present itself as ready.

Nothing is resumed: every attempt starts by wiping whatever is on disk — an abandoned download, a half-extracted tree, a system whose package step failed — and downloads again, because a rootfs missing pieces would otherwise fail the same way every time. Cancelling therefore costs the download when it is started over, and the download is the part that takes a network round trip rather than a minute of CPU.

LXC images ship `etc/resolv.conf` as a symlink into systemd-resolved under `/run`, which does not exist under proot. Install replaces that link with a plain file pointing at public DNS (`8.8.8.8` / `8.8.4.4`) so apt and HTTPS work.

Debian’s package manager relies on hardlinks when unpacking packages. On Android those often fail inside the app sandbox, so Kai Build starts proot with hardlink-to-symlink emulation (and the companion lstat fix) before any apt work. Without that, the base-packages step fails with a dpkg subprocess error even though `apt-get update` succeeded.

Kai also tells apt to keep running as (proot's fake) root instead of dropping to its `_apt` user for downloads. Work profiles, Dual Space, Private Space and system clones block that user switch, which otherwise makes `apt-get update` fail and every package look missing. The setting lives in the rootfs, so apt run from the Terminal or by an agent picks it up too.

Package installs hold the same lock the chat sandbox's apt uses, so a package install started in chat and one started here queue rather than corrupt the dpkg state.

### Coding agents

Five agents are offered — **Claude Code**, **Codex**, **Gemini CLI**, **OpenCode** and **Aider**. Each is installed with its own vendor command: two are shell scripts fetched over HTTPS, two come from npm, and Aider comes from pip. That is why curl, certificates, tar, coreutils, node and python are available in the base system; the npm-based installers install node and npm first if they are missing.

Installers write under `/root` on the rootfs (executable); only project folders live on the external bind. An agent counts as installed only when its binary is actually reachable afterwards, regardless of what the installer's exit code claimed; agent state is probed from the live environment rather than remembered in a file, and the probe is one round trip that asks about all of them at once.

An agent that fails to install does not stop the ones after it: the failure is recorded as a message naming the agent, and the rest of the ticked set still installs.

Vendor install pages often show both a Unix `curl | bash` line and a Windows PowerShell line. Only the Unix line is valid inside Kai Build — pasting both into the terminal will run the PowerShell tokens as shell commands and print `irm`/`iex: not found` after the real installer finishes.

Agents can be added after setup from inside a project: the plus button that starts a session lists every agent alongside a plain shell, whether or not it was ticked during setup.

### Projects

Every project is a folder under the Linux home's `projects` directory (`/root/projects` inside Debian). The project list is that folder listing, so anything created in the shell shows up. A plus button in the top bar opens a small dialog for the name; creating the folder drops the user straight into its terminal — no other steps. Names are sanitized to a safe folder name: lowercased, runs of anything other than letters, digits, dot, dash and underscore collapsed to a dash, trimmed of leading and trailing punctuation, and capped in length. A name that sanitizes to nothing, to `.` or to `..` is refused, and so is one that already exists.

Each row carries an overflow menu with the two things that can be done to the folder itself — **rename** and **delete**. They sit behind the menu rather than on the row so that tapping a project stays what it looks like: opening it. A row whose project still has shells open says how many, because the list is the only place to find them from; opening it goes back to those rather than starting another.

Renaming opens on the current name, and its button stays disabled while the field is empty or still holds the old name. A name that sanitizes to nothing, or that already belongs to another project, is refused — the dialog closes and the row keeps the name it had. Deleting asks first and names the folder it removes.

Both close the project's sessions, because both move the ground out from under them: the shells are rooted in that folder, and one whose working directory has been renamed or unlinked is no longer a session anybody can use. Deleting removes the folder and everything in it, following no symlinks on the way — an agent's project can hold a link into the rootfs, and a delete that followed one would take the Debian install with it. Projects created outside the app keep whatever name the shell gave them: the list addresses a folder by the name it shows.

A **Debian system** card below the list is the home for everything about the Linux install rather than the projects: which Debian it is, how many packages are in it, how much space the system and the project folders take, how much room is left on the device, and the uninstall action. The facts are read straight off the rootfs, so the card is current every time the list is shown.

Opening Kai Build answers "is Linux installed?" from a marker file and shows the project list straight away. Everything that costs real work — probing each agent by running it, and measuring how much disk the system and projects take — happens right after and fills the card in a moment later, so an installed system never shows the setup screen while it is being checked.

Uninstalling closes every session first, then deletes the system. Project folders are kept: they live outside the rootfs on purpose, and reappear as the same list when Linux is installed again.

### Terminal sessions

A project can have **several shells open at once** — one running an agent, another for git — switched from a chip strip at the top of the terminal. Each chip is labelled with what it runs: an agent's product name, or "Shell". A chip carries a close button, and a plus button at the end of the strip starts another session, asking which agent (or a plain shell) first.

Closing a session ends its shell and everything running inside it. Closing the last one leaves the project open with an empty terminal rather than stepping back to the list — the back arrow is the way out.

**Stepping out of a project leaves its shells running.** A build, a test run or an agent mid-task keeps going while the user reads something else or works in another project, and coming back finds the chips exactly as they were, with the same one in front. Closing a session is the user's decision, made from its chip; nothing else ends one. The exception is the backstop: a project nobody has reopened for half an hour has its shells closed, because each one is a proot process with a shell behind it and a project left overnight is not one anybody is still waiting on. Reopening the project at any point during that half hour calls it off.

An **agent session** runs that agent's CLI in the project folder. While the CLI is running it owns the shell, so a submitted line is written to its input rather than run as a new command — that is how the user talks to a running agent, and the shell is free for ordinary commands again once the CLI exits. A plain shell session is warmed at startup so the first command does not pay proot's launch cost, and it starts in the project folder.

Commands have generous ceilings rather than none: a user command is cut off after thirty minutes, an agent after a day. A session has no cancel button — ending the session is the way to stop what it is running.

### Project files

Next to the terminal sits **Files**: the same file browser the chat sandbox uses, pointed at Kai Build's Debian instead. It opens on the project's own folder, and navigates the same way it does in chat: the breadcrumbs run all the way up to the filesystem root, so an agent's config under the home folder, `/etc`, or anything else in Debian is a crumb away rather than shell-only. Where it starts is the only difference between the two.

It does everything it does in chat: open a file in the built-in editor and save it back — including the extension-less and unfamiliar-extension files a project is full of, since what counts as text is decided from the bytes rather than the name — hand a file to another app on the device, import a file from device storage into the directory on screen, rename, delete, and re-list itself silently whenever the tab becomes visible, so files an agent just wrote show up without a manual refresh. Files are read and written directly rather than through a shell, so none of it depends on a session running.

Switching to Files leaves every session running — coming back finds the terminal exactly as it was, and Files finds the directory it was left in, wherever in Debian that was.

### Terminal

A session's terminal is a **line-based transcript**, not a character grid. The shell runs without a pseudo-terminal, so there is no cursor to place, no full-screen painting and no key-at-a-time input: what the terminal shows is the list of lines the session has produced, each command echoed above the output it produced, and a command line at the bottom for the next one.

Each line is drawn in a monospace face, twelve points, one line per row, in one of three colours: the command the user submitted, the ordinary output, and whatever the command wrote to its error stream. The list scrolls to the newest line as output arrives, so a long build keeps its tail in view. It holds the last five hundred lines and drops the oldest beyond that; a single command's output is itself capped at about fifteen thousand characters, so a runaway command cannot push everything else out of the transcript.

The command line at the bottom takes up to three lines of text, submits on its button, and clears itself afterwards. Submitting while a process already owns the shell — an agent CLI, a build — sends the text to that process's input instead of starting a new command.

The transcript is per session and lives as long as the session does: switching chips or stepping out of the project and back finds the same scrollback.

### Repainting

Output is appended to the transcript as it arrives and the list is kept scrolled to the tail. There is no frame coalescing to speak of, because there is no cell grid to repaint: a burst of output adds rows to a lazily rendered list, which is what keeps a flooding command from stalling the rest of the app.

## Behavior

- **Android only** — the entry button is hidden elsewhere, and the environment itself is a no-op there.
- **Network required** — the rootfs download and every agent installer need HTTPS outbound access.
- **Disk** — expect roughly 100 MB for the base system, more per agent; the system card reports the real figure once Debian is installed.
- **Shared or separate** — Kai Build shares its rootfs with the chat sandbox when that sandbox is Debian, and has its own when it is Alpine. Project folders are the same either way.
- **Projects survive uninstall** — removing Linux deletes the Debian system, not the user's project folders. When the system is shared, removing it from either surface removes it for both. Deleting a project is the other way round: it takes that folder only, and leaves the system alone.
- **Files are ordinary files** — project folders live in app external storage, so the browser reads and writes them directly, and a file handed to another app opens as itself.

## Limitations

- **No pseudo-terminal** — programs that insist on a real terminal do not run properly: full-screen TUIs, anything that puts the terminal into raw mode, password prompts, and interactive wizards. An agent CLI runs, but it is being fed lines rather than keystrokes.
- **No key input** — there is no Ctrl, Esc or arrow keys, so readline history, tab completion and Ctrl-C are unavailable from the app. Interrupting a command means closing its session.
- **Escape sequences are shown, not interpreted** — colour, cursor movement and progress-bar codes arrive as text, so a program that colours its output looks noisier here than on a desktop.
- **No resize** — the shell has no terminal geometry to report, so a program that asks how wide the screen is gets no answer and formats for a default width.
- **Command ceilings, not cancellation** — a user command is stopped after thirty minutes and an agent after a day; there is no way to interrupt one sooner without closing the session.
- **Transcript is bounded** — the last five hundred lines per session, and about fifteen thousand characters per command's output. Anything earlier is gone, not scrolled away.
- **Cancel is cooperative** — cancelling an install takes effect while downloading and between install steps, not in the middle of a running `apt-get`.
- **Background** — long installs or agent sessions can be killed if Android reclaims the process (foreground service is planned). Sessions survive leaving a project but not the app being killed, and a project left alone for half an hour has its shells closed for it.
- **The file browser cannot create** — it renames, deletes, edits and imports, but only works with entries that are already there; new files and folders come from a shell.
- **Nothing is off limits in the browser** — it reaches the whole Debian tree, including system files a stray delete would break. Only the tree's own roots (the rootfs, the projects folder, `/tmp`) are protected from rename and delete.
- **Symlinks out of their root** are listed but not followed — a link that resolves outside the rootfs, the projects folder or `/tmp` does nothing when opened or deleted.
- **Sessions cost memory** — each one is a proot plus a shell, so a phone will not hold many at once. They accumulate across projects until closed or reaped, so the count on the project list is worth reading.
- **proot constraints** — same class of limits as the chat sandbox (no OpenSSH ControlMaster, no real mounts, some modern syscalls may fail).

## Key Files

| File | Purpose |
| --- | --- |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/KaiBuildController.kt` | Environment interface plus the no-op used off Android. |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/build/KaiBuildState.kt` | The single state snapshot the screen renders, including install stages and session info. |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/build/BuildAgents.kt` | The five installable coding agents, their install commands and the detection probe. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/KaiBuildController.android.kt` | Android implementation. Resolves Debian's one install directory and wires the two-way notifications with the chat sandbox, checked at fire time because the sandbox can be pointed at that install and away from it while the app runs. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/linux/LinuxInstalls.kt` | Which install directory a distribution lives in. Both surfaces resolve against it, which is what makes "Kai Build's Debian" and "a Debian shell integration" the same rootfs. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/build/runtime/BuildEnvironmentManager.kt` | Agent installs, agent detection, projects, system facts, and the live sessions. Delegates the rootfs itself to the shared installer, and reports install/uninstall back so a shared sandbox notices. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/build/runtime/BuildFileBrowser.kt` | Kai Build's side of the file browser: listing, read/write, rename, delete, open-with, over the shared `GuestFileMap`. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/linux/LinuxInstaller.kt` | Shared download → extract → configure → base packages, used by both surfaces. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/linux/DistroSpec.kt` | `DebianSpec`: LXC index resolve, architecture names, dpkg fixes, the apt setting that keeps it root, and the `--link2symlink`/`-L` flags apt needs on Android. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/linux/LinuxPaths.kt` | Where the rootfs, projects folder, home and `/tmp` live, and the marker that says an install exists. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/linux/ProotLauncher.kt` | The proot invocation and the guest environment every command and session runs with. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/linux/TarExtractor.kt` | Rootfs extraction, the resolv.conf replacement, and permission fixes. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/sandbox/SessionShell.kt` | One session: runs commands and appends them, with their output, to the transcript the terminal renders. |
| `composeApp/src/androidMain/kotlin/com/kainext/duo/sandbox/PersistentSandboxShell.kt` | The long-lived shell behind a session: framing, stdin, signals and reset. |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/TerminalLine.kt` | The three transcript line kinds — command, output, error. |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/ui/build/KaiBuildScreen.kt` | The whole surface: setup, project list, system card, session chips, the terminal transcript and command line, and the files pane. |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/ui/build/KaiBuildViewModel.kt` | Screen state, delegated to the controller. |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/ui/sandbox/SandboxFileBrowserScreen.kt` | The reused browser UI, rooted at the open project here. |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/FileBrowserSource.kt` | The browsable-tree contract shared with the chat sandbox's file browser. |
| `composeApp/src/commonMain/kotlin/com/kainext/duo/ui/chat/composables/EmptyState.kt` | Hosts the "Open Kai Build" entry button. |
