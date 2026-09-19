# Deploying BeastarsBot

The bot logs to **stdout only**. There is no file appender anywhere in the
application, and that is deliberate: the target host is a Raspberry Pi on flash
storage, and a continuously-appended log file is exactly the write pattern the
rest of this project avoids (see the `audit_logs` capped collection).

Log retention is therefore a **deployment** concern, not an application one.
Whatever captures stdout is responsible for bounding it. Pick one of the two
setups below. The first is strongly preferred.

---

## Option 1: systemd (recommended)

```bash
sudo cp deploy/beastarsbot.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now beastarsbot
```

Edit `User=`, `WorkingDirectory=` and `ExecStart=` in the unit first.

```bash
systemctl status beastarsbot      # is it up
journalctl -u beastarsbot -f      # follow logs
journalctl -u beastarsbot -n 200  # recent history
journalctl -u beastarsbot -p err  # errors only
```

### Cap journald, and do not skip this

journald rotates, but its **default ceiling is 10% of the filesystem**, which on a
32 GB Pi is ~3 GB of logs. Bounded, but not by much. Pin it:

```bash
sudo nano /etc/systemd/journald.conf
```

```ini
[Journal]
Storage=persistent
SystemMaxUse=200M
SystemMaxFileSize=20M
MaxRetentionSec=1month
```

```bash
sudo systemctl restart systemd-journald
journalctl --disk-usage        # verify
```

That is the whole retention story for this setup. Nothing else to maintain.

---

## Option 2: nohup + logrotate

Only if you are not using systemd. `nohup … > out.log` grows without limit.

```bash
sudo cp deploy/logrotate-beastarsbot /etc/logrotate.d/beastarsbot
sudo logrotate --debug /etc/logrotate.d/beastarsbot   # dry run, changes nothing
```

Adjust the path at the top of the file to wherever stdout is redirected.

`copytruncate` matters here: the JVM keeps the file descriptor open, so the log
must be truncated in place. A plain rename would leave the process writing to an
unlinked inode and the freshly "rotated" file would stay empty forever, which is
failure mode where you think logging works until you need it.

---

## Verifying retention actually works

Whichever option you chose, confirm it rather than assuming:

```bash
# systemd
journalctl --disk-usage
journalctl --vacuum-size=200M     # force a prune; should report freed bytes

# nohup
ls -lh /home/pi/BeastarsBot/out.log*
sudo logrotate -fv /etc/logrotate.d/beastarsbot   # force one rotation
```

---

## Log volume

Already trimmed in `src/main/resources/logback.xml`: the MongoDB driver is at
`WARN`/`ERROR`, JDA and Reflections at `WARN`. Steady-state output is the bot's
own `BunnyLog` lines plus genuine warnings, which is low.

If volume ever becomes a problem, raise `org.bunnys` from `DEBUG` to `INFO`
there. That is the single biggest lever.

> **Note:** `src/main/resources/application.yml` looks like it configures logging
> but is **inert**: nothing reads it (it is Spring-shaped config in a non-Spring
> project). `logback.xml` is the live configuration. Worth deleting to avoid
> someone editing the wrong file, but left in place pending your call.
