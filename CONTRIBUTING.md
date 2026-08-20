# Contributing

## This repository is a mirror

Development happens in a private working repo that also holds packet captures,
scratch notes and decoding tooling. What you see here is an allowlisted export of
the parts that make sense to publish.

The practical consequences:

- **Pull requests are welcome**, but they are applied upstream by hand and arrive
  back here in the next sync. Your change will land; your commit SHA will not.
- **Direct pushes to this repo get overwritten** by the next export. If you have
  commit access, change the upstream, not the mirror.
- Anything referenced from the docs but missing from the tree was left behind on
  purpose, not by accident.

## Adding a lightstick

The interesting contribution. Each device is a `DeviceProfile` plus a
`LightstickProtocol` in [`app/src/main/kotlin/.../device/profiles/`](app/src/main/kotlin/com/orangechuice/lightstick/device/profiles),
a registry entry, and a unit test that pins the wire bytes. Existing profiles run
40–120 lines each, and the tests are the specification — read those first.

A profile needs three things: the GATT service and characteristic to write to, the
write type the firmware expects, and a function turning a `LightState` into a frame
that stick accepts. If you have hardware working, a profile plus a test pinning its
bytes is a complete contribution on its own.

**Please do not commit packet captures.** They contain every Bluetooth exchange the
capturing phone made — headphones, watch, car, everything paired.

## Before opening a PR

```
./gradlew test
```

Match the surrounding style: comments explain *why* a byte or a timing exists,
not what the line does.
