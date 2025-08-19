# SlopShell 🍲

*Once upon a time, this was ConnectBot - a respectable SSH client for Android. Now? It's SlopShell, because sometimes you need to embrace the chaos.*

## What is this?

SlopShell is what happens when you take a perfectly good SSH client and turn it into a playground for half-baked ideas about Android-based Claude AI interactions. It's not pretty, it's not production-ready, but it's *interesting*.

Originally forked from ConnectBot, this project has mutated into a testbed for:
- Real-time git monitoring from your phone (because why not?)
- Claude AI integration experiments 
- SSH session shenanigans
- Whatever other Android/terminal/git mashup ideas bubble up

## The Git Monitor Thing

The crown jewel of our slop: a real-time git status monitor that runs on your Android device and connects to a lightweight server on your workstation. Watch your repository changes live, stage files from your phone, and generally feel like you're living in the future (or a debugging nightmare).

### Features that Actually Work
- Tree view of git changes with fancy folder icons (📁/📂)
- Real-time WebSocket updates when files change
- Pulsing animations for recent changes (very important)
- Stage/unstage files from your phone
- Swipe to refresh (revolutionary)

### Running the Server
```bash
cd git-monitor-server
./gradlew run --args="--port 9090 --repo /path/to/your/repo"
```

Then connect from the Android app to `http://your-ip:9090`

## Building This Mess

```bash
# Android app
./gradlew installVersionADebug

# Git monitor server
cd git-monitor-server
./gradlew build
```

## Why "SlopShell"?

Because this is where perfectly good code comes to get experimental. It's the coding equivalent of throwing everything in a pot and seeing what happens. Sometimes you get a delicious stew, sometimes you get... well, slop.

## Original ConnectBot Heritage

This project stands on the shoulders of ConnectBot, which was actually a well-engineered SSH client. We've kept the core SSH functionality while bolting on our Frankenstein additions. The original team would probably be horrified, but here we are.

## Contributing

Feel free to throw your own ingredients into the pot. Just know that code quality standards are... relaxed. If it compiles and does something interesting, it's probably good enough for SlopShell.

## License

Same as ConnectBot - Apache 2.0. Because even slop needs proper licensing.

---

*Remember: This is a playground, not a product. Use at your own risk, amusement, and/or dismay.*