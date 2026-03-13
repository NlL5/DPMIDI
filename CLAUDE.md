# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DPMIDI is an Android RTP MIDI application that enables network-based MIDI communication compatible with Apple's Network MIDI (Bonjour/mDNS). It implements RFC 4695 for RTP MIDI protocol. Written in Java.

## Build Commands

```bash
./gradlew assembleDebug      # Build debug APK
./gradlew assembleRelease    # Build release APK
./gradlew build              # Full build
./gradlew clean              # Clean build artifacts
```

Min SDK 21, Target/Compile SDK 34, Gradle 8.7, AGP 8.5.2. Version defined in `gradle.properties`.

## Module Structure

Two-module Gradle project:

- **`:app`** — Android application UI layer (`com.disappointedpig.dpmidi`). Activities, services, UI components.
- **`:midi`** — RTP MIDI protocol library (`com.disappointedpig.midi`). Standalone protocol implementation with no app-layer dependencies.

## Architecture

**Event-driven** using GreenRobot EventBus as the communication backbone between modules.

### midi module (protocol layer)
- `MIDISession` — Singleton managing session lifecycle, Bonjour discovery (NsdManager), and stream coordination
- `MIDIStream` — Individual MIDI stream handling with synchronization and latency compensation
- `MIDIControl` — Apple MIDI control messages (Invitation, Acceptance, Rejection, End, Sync)
- `MIDIMessage` / `RTPMessage` — MIDI and RTP packet parsing/creation
- `DataBuffer` / `DataBufferReader` / `OutDataBuffer` — Binary serialization utilities
- Events are split into **public** (consumed by app: `MIDIReceivedEvent`, `MIDIConnectionEstablishedEvent`, etc.) and **internal** (`PacketEvent`, `StreamConnectedEvent`, etc.)

### app module (UI/service layer)
- `ConnectionManagerService` — Foreground service managing MIDI connections, WiFi/wake locks
- `ConnectionManager` — Singleton managing connection states and heartbeat logic
- `MainActivity` — Main UI with connection controls
- `AddressBook` — MIDI device address management (persisted via WaspDB)
- `DPMIDIApplication` — App lifecycle, SharedPreferences for state (MIDI state, background mode, reconnect preference)

### Data flow
Network packets → `MIDISession` → `MIDIStream` → EventBus events → `ConnectionManager`/UI

## Key Dependencies

- **EventBus 3.3.1** — All inter-component communication
- **WaspDB** — Address book persistence
- **AndroidPdfViewer** — PDF display
- **AndroidX** — AppCompat, RecyclerView, ConstraintLayout, Material Design 3