# Using the Lightstick Controller

What every control on the screen actually does. For building and installing the
app, see [README.md](README.md).

---

## The colour controls

Three sliders and a row of preset swatches sit below the pattern list.

| Control | What it sets |
|---|---|
| **Hue** | The colour itself, all the way round the wheel |
| **Saturation** | How much colour there is — full left is white, full right is vivid |
| **Brightness** | The ceiling for everything below it. A pattern that "goes to full" goes to *this* |
| **Presets** | Eight fixed colours; tapping one sets hue and saturation, leaving brightness alone |

Each slider's track is drawn in the colours it selects, and the saturation and
brightness tracks both end on the colour you've currently got — so the sliders
are the preview. There's no separate swatch.

**Hue, saturation and the presets disappear on Rainbow, Timeline, Palette, Random
and Spectrum.** Those five carry their own colours, so the sliders would not reach
the light — the same reasoning that hides sensitivity on the beat-driven modes. Only
the brightness slider is left, and its track goes black-to-white rather than
showing a colour the stick isn't using. Brightness applies to every pattern and
every mode, so it is never hidden.

Brightness is done in software — the stick has no brightness command, so the app
scales the RGB values it sends. At very low settings you may see colour banding;
that is the hardware, not a bug.

---

## Patterns

| Pattern | What it does | Uses hue | Uses brightness |
|---|---|---|---|
| **Manual** | Holds the colour you picked, and nothing else | ✅ | ✅ |
| **Breathe** | Fades up and down, one full breath every 3 seconds, all the way to dark at the bottom | ✅ | ✅ (the peak) |
| **Rainbow** | Sweeps the entire hue circle every 5 seconds | ❌ own colours | ✅ |
| **Strobe** | Hard on/off, 5 flashes a second, lit for about a third of each flash | ✅ | ✅ |
| **Timeline** | A 6-second loop through pink → amber → blue → purple, fading smoothly between them | ❌ own colours | ✅ |
| **Sync to music** | Listens through the microphone — see below | depends on mode | ✅ |

Changing colour or brightness while a pattern is running retunes it in place; it
does not restart the cycle. Nudging brightness during a rainbow changes the
brightness without snapping the hue back to the beginning.

**Each stick comes back the way you left it.** Colour, brightness, pattern and
music mode are saved per stick and restored the next time that same stick
connects — including after the app restarts. Two sticks each keep their own set,
so they reconnect to different colours if that is how you left them. "Forget" on
the last-used device card also throws away its saved settings.

---

## Sync to music

Selecting it needs two things: a **connected stick** and **microphone
permission**. The mic runs only while this pattern is selected and a stick is
connected, and it stops the moment either goes away.

Nothing is recorded and nothing leaves the phone. The audio is analysed frame by
frame and thrown away.

### The eight modes

| Mode | What it does | Colour comes from |
|---|---|---|
| **Pulse on beat** | Snaps to full brightness on each kick drum, then falls away over about 0.2 s. Beats can't fire closer together than 200 ms | Your colour |
| **Strobe** | A hard flash on each kick — full for 70 ms, then black, with no fade either side. Same beats as Pulse, sharper edges | Your colour |
| **Flip** | Snaps between your colour and its opposite on every beat, at constant brightness. The colour change alone carries the beat | Your colour, plus its half-turn opposite |
| **Palette** | Steps one colour forward on every beat, through pink → blue → gold → purple → green, pulsing slightly between steps | Fixed palette — hue and saturation hidden |
| **Random** | Jumps to a new, far-apart colour on every beat, pulsing slightly between them | Picks its own — hue and saturation hidden |
| **Loudness** | Brightness follows how loud the room is, continuously. No beat detection involved | Your colour |
| **Bass only** | Brightness follows the bass band alone, ignoring mids and treble. Built for crowds — see below | Your colour |
| **Spectrum** | Bass drives red, mids drive green, treble drives blue. The colour *is* the music | The music — hue and saturation hidden |

The first five are driven by **beat detection**; the last three track **levels**.
They're grouped that way on screen for a reason: sensitivity applies to the level
group only. The same split explains most of what you'll see — on music with no
clear kick, Pulse sits still while Loudness keeps moving.

**Flip on a white or grey colour holds steady instead of flipping.** Grey has no
hue to turn around, so both halves come out the same. Turn saturation up and the
alternation appears. This is deliberate: the alternative way to build an
"opposite" turns white into black and would switch the light off every other beat.

### Which mode for a packed venue

**Bass only** is the one built for it. A crowd's noise — screaming, chanting,
clapping — lands almost entirely in the mid and treble bands, while the kick drum
has the low end more or less to itself even in a full arena. Loudness and Spectrum
both read the contaminated bands and will chase the crowd as much as the music;
Bass only doesn't.

**Strobe** and **Flip** are the most visible from a distance in a dark room, since
both trade in hard edges rather than fades.

### Sensitivity

**Only appears on Loudness, Bass only and Spectrum.** The beat-driven modes
respond to detected beats, and the beat detector has its own threshold that
nothing here reaches — so on those the control isn't shown at all. If Pulse feels
wrong, sensitivity was never the fix.

| Setting | What it does |
|---|---|
| **Auto** (default) | Watches both the quiet and the loud end of the last few seconds and stretches the light across whatever range the music is actually using |
| **Manual** | A slider, scaling the level by about 0.4× to 3× |

Auto is the one to use. The app has always adjusted to room volume on its own —
that part needs no help — but volume is not the same as *range*. Loud, heavily
compressed music never really gets quiet, so the light ends up moving inside a
narrow band near the top and looks like it's barely reacting. Auto finds that
band and opens it out to the full brightness range. On the test signal it roughly
tripled how much of the range the light actually used.

Manual can't do this at any position: turning the slider up moves the whole
window up until it clips, but it never widens it. Reach for Manual only when you
want the light deliberately held back or pushed hard, not to fix flatness.

### When there's nothing to hear

Silence settles to a dim resting glow at 12% brightness rather than going black,
so "the room is quiet" and "the app has died" don't look the same. Which colour
it rests on follows the same split as the controls:

| Mode | Rests on |
|---|---|
| **Pulse on beat**, **Strobe**, **Flip**, **Loudness**, **Bass only** | Your colour |
| **Palette** | Wherever the palette stopped — the colour that was lit when the music died |
| **Random** | Wherever it stopped, the same way Palette does |
| **Spectrum** | Dim white. The music *is* its colour, so with no music it doesn't invent one |

Brightness still sets the ceiling the 12% is taken from, on all eight.

---

## Reading the meter

Three bars and a dot sit at the bottom of the music card. They exist so you can
tell *why* the light is doing what it's doing.

| What you see | What it means |
|---|---|
| **Bass / Mid / Treble bars** | Energy in 20–150 Hz, 250 Hz–2 kHz, and 2.5–8 kHz |
| **Yellow dot** | Flashes on each detected beat |
| **"Listening"** | The mic is running and hears something |
| **"Listening — nothing to hear"** | Running, but the room is below the silence threshold |
| **"Not listening"** | The mic isn't running — check the connection and permission |
| **Red overload warning** | The input is maxed out — see below |

The bars are deliberately scaled to recent history, not to absolute volume. All
three sitting near full during a loud passage is normal.

If the bars are flat while music is clearly playing, the phone is not hearing it.
Mic placement is the usual cause — a hand, a pocket seam, or a table can cover
the mic entirely.

---

## Very loud rooms

At a concert or festival the microphone can max out. When it does, the peaks are
flattened before the app ever sees them, and the symptoms are specific:

- All three bars pinned at 100%
- Beat dot stops flashing
- Light sits at constant full brightness instead of pulsing

The red **"input overloading"** warning appears when this is detected. The fix is
physical — turn the phone away from the speakers, or put it in a pocket. Muffling
costs volume, which is exactly what's needed. Neither Auto nor the Manual slider
can help here; the detail is already gone before either of them applies.

---

## Screen off

Music sync keeps running with the screen off. A notification stays up while it
does — that's what keeps Android from cutting the microphone off, which it
otherwise does to backgrounded apps.

The mic stops when you switch to another pattern, disconnect the stick, or close
the app. Swiping the notification away does not stop it — that only hides it.
