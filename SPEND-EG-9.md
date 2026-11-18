# SPEND-EG-9: engine-gaps round EG-9

Inside the engine-gaps $17.50 hold ($3.25 left for EG-9). My estimate before launch was ≈ $0.93; guard cap $1.40.

## Hosts and hours (cost.py, Spot $/h at launch + 16 GB gp3 + one public IPv4)

| host | market | AZ | hours | cost |
|---|---|---|---|---|
| r8i-x1 (D0, D1, J1 x) | spot | eu-north-1b | 2.28 | $0.051 |
| r8i-x2 | spot | ap-south-1b | 2.10 | $0.087 |
| r8i-y1 | spot | eu-north-1c | 2.55 | $0.055 |
| r8i-y2 | spot | us-east-1c | 2.55 | $0.122 |
| r9g-x1 (reclaimed 22:28 PST) | spot | us-west-2a | 0.96 | $0.059 |
| r9g-x2 | spot | eu-central-1a | 2.10 | $0.140 |
| r9g-y1 | spot | eu-central-1a | 2.63 | $0.176 |
| r9g-y2 (reclaimed 22:28 PST) | spot | us-west-2a | 0.96 | $0.059 |
| r9g-x1r | spot | us-west-2a | 0.66 | $0.041 |
| r9g-x1s | spot | us-east-2c | 0.66 | $0.025 |
| r9g-y2r | spot | us-east-2b | 1.01 | $0.050 |
| r9g-y2s | spot | us-west-2d | 0.81 | $0.032 |
| **total** | | | **19.27 host-h** | **$0.898** |

- Spot only; no On-Demand. r9g.large and r8i.large only. The two reclaims cost the partial forks of one case each (kept
  in `superseded/`) and about $0.02 of reruns beyond the plan.
- Launch 21:35 PST Nov 17. Last host done 00:14 PST Nov 18; guard ALL DONE 00:14:11 PST.

## Teardown (00:15 PST) and sweep

- `teardown.sh` (tag Project=regulator-engine-gaps-eg9 only), in ap-south-1, eu-central-1, eu-north-1, us-east-1,
  us-east-2 and us-west-2: no instance left in pending/running/stopping/stopped/shutting-down. Spot requests: none open
  or active. Security groups deleted, then key pairs, then the local .pem (gone). Instances still listed, all
  `terminated`: i-0ca032e1629c194de, i-01b4be01a8cc391fb, i-087d2675946c11562, i-0a585c3471b2f9c1d,
  i-03e94eaf00114e842, i-0af87c3b6516a00ed, i-015223cfd3c25aed3, i-01b8b65b5ac458dc9. The other four
  (i-0adb298deecc46e47, i-0f79b77ae15bd3d54, i-0289eaf2ae4b4925c, i-0bc25478002b7004a) had terminated more than an hour
  earlier, so AWS no longer listed them.
- `sweep.sh`: 17 enabled regions swept for Project=regulator-engine-gaps-eg9 (instances, open/active Spot requests,
  security groups by tag and name, key pairs by tag and name, volumes): **sweep clean** at 00:17:06 PST (08:17:06Z).

## S3 archive

- Key `archive/engine-gaps-eg9-20261118.tar.zst` (Glacier IR), 96,105,607 B, sha256
  `f0a928c21249ee65e19e1c6153260944cc066b9e2e5681cf410f36caa6776b98`, 6,209 files. Verified with head-object (size and
  storage class match); the upload error file is empty.
- Contents: every host's pulled `runs/` (J1 JSON and logs, preflight, D0/D1 perfasm logs with the 20 raw `.perfasm.zst`
  and 20 `.xml.zst` files, D0n/D1n perfnorm JSON, HOSTINFO, host-prep, perf and hsdis checks), `superseded/`, the job
  files and appended D lines, the round's scripts (gen-jobs, summarize, diag, jvmwrap, host-prep, the D0/D1 readers),
  results tables, summary.json, guard.log, cost, teardown and sweep logs.
