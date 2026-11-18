# SPEND-EG-8: engine-gaps round EG-8

Tag `regulator-engine-gaps-eg8`, inside the engine-gaps $15 hold. Guard cap $3.60 (`kit-eg8/cap.txt`). The estimate
before launch was ≈ 57.5 host-h, ≈ $3.0 (≈ $3.3 with a reclaim allowance).

## Hosts and hours (cost.py: Spot price × hours + 16 GB gp3 + one public IPv4 each)

| host | AZ | instance | hours | cost | end |
|---|---|---|---|---|---|
| r9g-x1 | us-east-2b | i-0ca3a597f50199ff2 | 4.91 | $0.242 | done |
| r9g-x2 | eu-central-1a | i-0f8d675dd97d197ff | 4.91 | $0.329 | done |
| r9g-y1 | us-west-2c | i-04a46690b1fdabc9d | 2.44 | $0.132 | reclaimed |
| r9g-y2 | eu-central-1c | i-03d94f6a10eb8c56e | 4.71 | $0.328 | done |
| r9g-z1 | us-east-2b | i-0c64e68da65651636 | 5.09 | $0.250 | done |
| r9g-z2 | eu-central-1a | i-02155676e1275fdf5 | 5.09 | $0.341 | done |
| r8i-x1 | eu-west-3a | i-020837cdd2ff80d3b | 4.81 | $0.223 | done |
| r8i-x2 | ap-south-1b | i-0008b4b6c3c4d812c | 4.72 | $0.197 | done |
| r8i-y1 | ap-south-1a | i-06ce919ed6beb7031 | 4.62 | $0.213 | done |
| r8i-y2 | eu-west-2b | i-008486ecf11f0da19 | 1.36 | $0.071 | reclaimed |
| r8i-z1 | eu-west-3a | i-00aa9db04fa672765 | 4.91 | $0.228 | done |
| r8i-z2 | ap-south-1b | i-0816b653ed97008f4 | 4.81 | $0.201 | done |
| r8i-y2r | us-east-1b | i-0c61d7cb92763b56c | 1.57 | $0.074 | reclaimed |
| r8i-y2s | eu-central-1b | i-06fc50138c582b0a5 | 1.37 | $0.095 | done |
| r9g-y1r | us-east-2b | i-07416f9ae905e76a2 | 1.19 | $0.058 | done |
| r9g-y1s | us-west-2a | i-049a2d801e7562402 | 1.49 | $0.091 | done |
| r8i-y2t | eu-central-1b | i-0c89094f6e2ccc538 | 0.79 | $0.054 | done |

- **17 Spot instances, 58.8 host-h, $3.128** by cost.py (`kit-eg8/cost-final.txt`). Spot rates per AZ, read from the
  price history at launch, are in `rates.tsv`. The three reclaims cost about $0.15 in partial forks and boot time.
- Launch 15:08 PST. ALL DONE at 20:13:43 PST (04:13:43Z), after the guard terminated r9g-z2 following its last pull.
- Nothing was dropped; the guard never neared the cap.

## Teardown and sweep

- `teardown.sh` (20:14–20:15 PST) covered every region used: us-east-2, us-west-2, eu-central-1, eu-west-3, ap-south-1,
  eu-west-2 and us-east-1.
  - It found no live instances.
  - In each region it deleted the security group and the key pair, then the local .pem.
  - Its evidence showed no instances in a live state, and no security groups or key pairs.
- Spot requests:
  - Teardown cancelled eu-central-1 `sir-kqp81qnj` (the request of r8i-y2t's instance). Its evidence listed the request `active` a few seconds later; a recheck shows `cancelled` (instance-terminated-by-user).
  - Every other request is `closed`: instance-terminated-by-user, or instance-terminated-no-capacity for the three reclaims.
- **17-region sweep: clean** (`kit-eg8/sweep.log`, 04:15:19Z–04:16:57Z).
  - Nothing tagged or named `regulator-engine-gaps-eg8` is left: no instances, open or active Spot requests, security groups, key pairs or volumes.
- Instance states afterwards: all 17 instances are `terminated` or have aged out of `describe-instances`.
- No other runner's resources were touched; every action was scoped to this tag.

## Archive

- `archive/engine-gaps-eg8-20261117.tar.zst` (+ `.files.txt.zst`, `.tar.zst.sha256`) is in the ARCHIVE-S3.md bucket,
  Glacier IR.
  - 6,100,016 bytes, CRC64NVME `l8g5RX5sPGI=` (FULL_OBJECT).
  - sha256 `f506c2b63ac7f89b3ed08c1646486a8645ab6476e31d11a717187439af3f1731`, matched by a copy downloaded back from S3.
  - 15,171 files; the listing, the tar and the downloaded tar all match.
- Contents:
  - every host's JMH JSON and logs, plus the reclaimed hosts' superseded partial forks;
  - the job files as started and as rewritten for the replacements;
  - guard.log, cost.txt, cost-final.txt, teardown.log, sweep.log, results-tables.md, summary.json and inputs/cases.tsv;
  - the kit scripts, the bundle revision.txt files, and created.txt without its security-group (IP) lines.
  - Host names in HOSTINFO and host-prep-info, and IPs in the start logs, are masked.
- Local copy: `~/AgentScratch/regulator/main/engine-gaps/kit-eg8/archive/`.
