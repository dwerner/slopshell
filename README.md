[![Build Status](https://travis-ci.com/connectbot/connectbot.svg?branch=master)](
https://travis-ci.com/connectbot/connectbot)

# SlopShell

SlopShell (formerly ConnectBot) is a [Secure Shell](https://en.wikipedia.org/wiki/Secure_Shell)
client for Android that lets you connect to remote servers over a
cryptographically secure link. This fork is designed as a testbed for integrating
with Claude AI and exploring new interaction paradigms.


## Google Play

[![Get it on Google Play][2]][1]

  [1]: https://play.google.com/store/apps/details?id=org.connectbot
  [2]: https://developer.android.com/images/brand/en_generic_rgb_wo_60.png


## Compiling

### Android Studio

ConnectBot is most easily developed in [Android Studio](
https://developer.android.com/studio/). You can import this project
directly from its project creation screen by importing from the GitHub URL.

### Command line

To compile ConnectBot using `gradlew`, you must first specify where your
Android SDK is via the `ANDROID_SDK_HOME` environment variable. Then
you can invoke the Gradle wrapper to build:

```sh
./gradlew build
```

### Reproducing Continuous Integration (CI) builds locally

To run the Jenkins CI pipeline locally, you can use
`jenkinsfile-runner` via a Docker installation which can be invoked like
this:

```sh
docker run -it -v $(pwd):/workspace \
    -v jenkinsfile-runner-cache:/var/jenkinsfile-runner-cache \
    -v jenkinsfile-runner:/var/jenkinsfile-runner \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v $(which docker):$(which docker) \
    -e ANDROID_ADB_SERVER_ADDRESS=host.docker.internal \
    jenkins/jenkinsfile-runner
```


## Claude AI Integration Ideas

### Code Review & Change Management (Priority Features)

- **Git Monitor Web Server** (Runs on Workstation):
  - Lightweight web server that watches repository changes in real-time
  - WebSocket support for live updates as files change
  - REST API for git operations (stage, unstage, commit, rollback)
  - Serves mobile-optimized web interface
  - File system watcher that tracks ALL changes (Claude, vim, IDE, etc.)
  - Runs locally in the repo: `git-monitor --port 8080 --repo .`
  
- **Mobile Review Interface** (Android App or Browser):
  - Connects to workstation's Git Monitor server
  - Real-time diff streaming as Claude makes changes
  - Touch-optimized interface for code review
  - Pinch to zoom on diffs
  - Voice commands: "Show me what changed", "Approve this file", "Rollback"
  - Push notifications when significant changes detected
  - Works over local network or SSH tunnel
  
- **Live Change Visualization**:
  - Split screen: live terminal session + real-time diffs
  - Change heatmap showing which files are being modified
  - Timeline scrubber to replay changes
  - Side-by-side before/after view
  - Syntax-highlighted diffs with semantic understanding
  - Change velocity graph (changes per minute)
  
- **Remote Control Features**:
  - Stage/unstage files from mobile
  - Create commits with voice-to-text messages
  - Trigger test runs and see results
  - Pause Claude's work if something looks wrong
  - Create checkpoints before risky operations
  - Emergency rollback button

### Existing Tools That Can Be Used Today

- **GitWeb / cgit** - Lightweight web interfaces for git repos
  - Built into git, can run with `git instaweb`
  - Read-only view of repository state
  - Basic but functional mobile interface

- **Gitea / Gogs** - Self-hosted GitHub clones
  - Full web UI for repository management
  - Mobile-responsive design
  - Can run locally with `gitea web` or `gogs web`
  - Includes diff viewer, commit interface

- **ungit** - Visual git interface with web UI
  - `npm install -g ungit && ungit`
  - Real-time visualization of git state
  - Drag-and-drop staging
  - Works well on mobile browsers

- **git-webui** - Standalone web UI for git
  - `git webui` launches local web server
  - Clean interface for staging/committing
  - Mobile-friendly design

- **lazygit** with web streaming
  - Terminal UI that can be streamed via ttyd
  - `ttyd lazygit` creates web-accessible terminal

- **VS Code Server / code-server**
  - Full IDE in browser including git integration
  - `code-server --bind-addr 0.0.0.0:8080`
  - Mobile apps available (like Servediter)

- **File Watchers + Web Servers**
  - **watchman** (Facebook) + custom web frontend
  - **chokidar-cli** + WebSocket server
  - **entr** + webhook notifications
  - **inotify-tools** + simple HTTP server

- **Commercial Solutions**
  - **Working Copy** (iOS) - Full git client
  - **MGit** (Android) - Git client with web server mode
  - **GitJournal** - Mobile-first git interface
  
- **Change Monitoring Dashboard**:
  - Real-time notification of ANY file changes (Claude or manual)
  - Checkpoint system - create restore points before major changes
  - Visual timeline of all changes
  - Quick rollback to any checkpoint
  - Change statistics and metrics
  
- **Optional Claude Assistance**:
  - Toggle Claude suggestions on/off
  - Claude can explain changes if asked
  - Commit message suggestions (optional)
  - Risk assessment (can be disabled)
  
- **Hybrid Workflow**:
  - Review Claude's changes with standard git tools
  - Manual override for any Claude suggestion
  - Human-in-the-loop approval process
  - Export changes for review in other tools

### Voice & Natural Language
- **Voice-to-Command**: Speak natural language requests that Claude translates to shell commands
- **Command Explanation**: Claude explains what commands do before execution
- **Smart Autocomplete**: Claude-powered intelligent command suggestions based on context

### SSH Session Enhancement
- **Session Context Awareness**: Claude maintains context across SSH sessions
- **Error Diagnosis**: Real-time help when commands fail with suggested fixes
- **Script Generation**: Describe tasks in plain English, Claude generates shell scripts
- **Multi-Server Orchestration**: Natural language commands that execute across multiple servers

### Interactive Development
- **Claude Web Bridge**: 
  - Local web server that Claude can interact with via API
  - Database query interface for Claude to help with SQL
  - REST API testing and debugging with Claude assistance
  
- **Development Assistant**:
  - Real-time code review as you type in vim/nano
  - Claude suggests optimizations and bug fixes
  - Integrated documentation lookup
  
### Security & Monitoring
- **Anomaly Detection**: Claude monitors command patterns for unusual activity
- **Security Audit**: Natural language security assessment of server configurations
- **Log Analysis**: Claude parses and explains complex log entries

### Data Integration
- **Structured Output**: Claude formats command output into tables/JSON
- **Data Pipeline Assistant**: Natural language data transformation commands
- **Visualization Bridge**: Send command output to Claude for analysis and chart generation

### Workflow Automation
- **Task Templates**: Save common Claude-assisted workflows
- **Conditional Execution**: Claude interprets conditions in plain English
- **Scheduled Tasks**: Natural language cron job creation

### Learning & Documentation
- **Interactive Tutorials**: Claude guides through complex tasks step-by-step
- **Command History Analysis**: Claude learns from your patterns to improve suggestions
- **Auto-Documentation**: Claude documents your shell sessions automatically

## Translations

If you'd like to correct or contribute new translations to ConnectBot,
then head on over to [ConnectBot's translations project](
https://translations.launchpad.net/connectbot/trunk/+pots/fortune)
