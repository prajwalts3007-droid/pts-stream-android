# Read-only GitHub runner media-size audit — 12 November 2026

No compression settings, pipeline workflows, production jobs, accounts, or media were changed. This audit concerns processed media, not the Android APK. Values below use GiB (2^30 bytes).

## Observed jobs

| Catalogue title | Source bytes | Output bytes | Source → output GiB | Saved |
| --- | ---: | ---: | --- | ---: |
| I'm Game | 6,700,231,736 | 1,831,294,992 | 6.240 → 1.706 | 72.7% |
| Jailer 2 | 9,733,177,336 | 1,653,027,324 | 9.065 → 1.540 | 83.0% |
| The Vvaan: Force of the Forrest | 2,343,198,625 | 854,141,470 | 2.182 → 0.795 | 63.5% |

The first two sizes and savings were checked against both the read-only job database and successful Actions compression reports. The third row is a database observation only.

## Why the outputs can still be large

- The executed encoder uses **libx265, CRF 25, slow, maximum height 540p, no upscaling**, not a target file size. The code at pipeline commit `9c43136053` and the actual run logs agree. CRF output varies with duration and visual complexity; resolution alone does not fix the bitrate or size.
- It retains one useful audio track **per language**, not one audio track in total. Compatible low-bitrate stereo AAC may be copied; other audio is encoded to stereo AAC with a nominal 128 kbps target per language.
- Read-only `ffprobe` of the existing I'm Game staging file found a 10,297.811-second duration, one 960×540 HEVC video track at 751,773 bps, and five AAC tracks (Malayalam, Hindi, Kannada, Tamil, Telugu), each about 130–131 kbps. Their summed reported bitrate is 652,659 bps: approximately 840 MB of audio over that duration, about 46% of the complete file. This is an estimate from stream bitrates, not a byte-exact demux measurement.
- Jailer 2's catalogue reports 9,831.859 seconds and five AAC language tracks. Five nominal 128 kbps tracks imply roughly 750 MiB of audio, about 48% of that output. Its executed video profile was 960×520, HEVC CRF 25 slow.
- Remuxing to MP4 does not itself re-compress already encoded video/audio. Container overhead exists, but these observations do not suggest it explains hundreds of megabytes.

Evidence: successful pipeline Actions runs `43244082743` (I'm Game) and `43242751251` (Jailer 2), the job/catalogue database opened with `mode=ro`, and header/stream metadata from the existing staging file. No new download, encode, job retry, or media copy was performed for the audit.

These examples are smaller than their inputs. They explain the apparent size floor for long, multilingual outputs; they do not prove why any different file grew. Investigating another example requires its title/job and source/output sizes. Reducing languages, bitrate, resolution, or changing CRF would change the requested profile and was deliberately not done.
