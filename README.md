# BeastarsBot — Feature Guide

Everything the bot does, and how to use it.

---

## Contents

1. [Two ways to run any command](#1-two-ways-to-run-any-command)
2. [Finding your way around](#2-finding-your-way-around)
3. [Information commands](#3-information-commands)
4. [Wiki, images and albums](#4-wiki-images-and-albums)
5. [Manga reader](#5-manga-reader)
6. [The Leg economy](#6-the-leg-economy)
7. [Admin dashboard](#7-admin-dashboard)
8. [Logging and crash reports](#8-logging-and-crash-reports)
9. [Setting up a new server](#9-setting-up-a-new-server)
10. [Limits and defaults](#10-limits-and-defaults)
11. [Common questions](#11-common-questions)

---

## 1. Two ways to run any command

Every command works two ways, and both do exactly the same thing:

| Form | Example | When to use it |
|---|---|---|
| **Slash** | `/manga source:MD chapter:5` | Normally. You get autocomplete and dropdown menus. |
| **Mention** | `@BeastarsBot manga source:MD chapter:5` | If slash commands aren't showing up for you. |

The mention form accepts the same arguments, written the same way:

```
@BeastarsBot wiki query:Legoshi
@BeastarsBot wiki "Legoshi and Haru"     ← quotes keep spaces together
@BeastarsBot wiki Legoshi and Haru       ← also works, absorbs the whole phrase
@BeastarsBot leg                          ← lists the options if you forget one
```

**One exception:** `/admin roles` opens a form, and Discord only allows forms from slash
commands. Ask for it by mention and the bot will tell you to use the slash version.

> **There is no `!` prefix and there never will be.** Discord no longer lets the bot read
> ordinary messages — it only sees a message that mentions it by name. That's why the
> mention form exists.

**Where commands work:**

| Works in DMs | Server only |
|---|---|
| `/help` `/ping` `/uptime` `/avatar` `/info user` `/wiki` `/manga` `/randompage` | `/leg` `/image` `/imgur` `/admin` `/info server` |

---

## 2. Finding your way around

### `/help`

Opens the command browser. One category per page, with five controls beneath it:

| Control | What it does |
|---|---|
| **First** / **Last** | Jump to either end of the category list |
| **Previous** / **Next** | Step one page |
| **Browse** | Opens a dropdown — pick any category and go straight there |
| *Dropdown below* | Pick any **command** for its full detail card |

The index is deliberately short: each command shows what it does and either its usage or
its branch names. Everything else lives one click away on the detail card.

### The detail card

Picking a command from the dropdown shows:

- Its **slash usage** and **mention usage**
- A **worked example** you can copy
- **Every parameter** — required or optional, what it means, and **exactly which values it accepts**

That last part is the point. `/manga source:` accepts four values and the card lists all
of them with their meanings, so you never have to guess.

**Back to Command List** returns you to where you were.

### Straight to one command

```
/help command:manga
```

Autocompletes over every command name and jumps directly to its detail card.

**Reading the syntax:** `<angle brackets>` are required, `[square brackets]` are optional.

---

## 3. Information commands

### `/ping`

Two latency figures, because they measure different things:

- **Gateway** — the bot's live connection to Discord
- **REST API** — a fresh round trip, which is what actually predicts command speed

Plus a plain-English verdict. *5-second cooldown.*

### `/uptime`

How long the bot has been running, as `3d 4h 12m 8s`, plus when it last started, how many
servers it's in, its version, and who built it.

### `/avatar [user] [global]`

| Argument | Effect |
|---|---|
| *(nothing)* | Your own avatar |
| `user:@someone` | Theirs |
| `global:true` | Their account-wide avatar rather than their server one |

If someone has **both** a server avatar and a global one, you see **both at once** — the
requested one large, the other as a thumbnail — with a link button for each. That's usually
why you ran the command.

### `/info user [user]`

A full profile card: account badges, when they joined Discord, when they joined this
server, nickname, account type, their colour (clickable), their ID (links to their
profile), highest role, full role list, boosting since, and key permissions.

Empty fields are **hidden rather than left blank**, so a card is only as long as the person
warrants. Works on people **outside this server** too — you just get the account half.

Run it on **the bot itself** and you also get its version and developer.

> **Not included:** online status, device, or "listening to Spotify". Those need a
> privileged Discord permission that is no longer granted to bots like this one. Showing a
> permanent "Offline" would be worse than showing nothing.

### `/info server`

Owner, member count, roles, channel breakdown, boosts and tier, verification level,
content filter, language, AFK channel and timeout, description, features, and the server
banner. Server-only.

---

## 4. Wiki, images and albums

### `/wiki query:<something>`

Searches the Beastars Fandom Wiki. **Autocompletes as you type** — start typing a
character name and pick from the suggestions.

Returns a summary with a thumbnail and a **Read Article** button. *5-second cooldown.*

### `/image` — custom image shortcuts

Save an image under a short name so anyone can call it up later. This is your
"custom commands that post a gif" feature.

| Command | Who | What |
|---|---|---|
| `/image add name:<name> url:<url>` | **Admin** | Save or update a shortcut |
| `/image get name:<name>` | Anyone | Post it — **autocompletes** over saved names |
| `/image remove name:<name>` | **Admin** | Delete it |
| `/image list` | Anyone | Every shortcut on this server |

**The URL must be a direct image link** — ending in `.png`, `.jpg`, `.jpeg`, `.gif`,
`.svg` or `.webp`, or a Discord attachment link. A link to a *page* containing an image
won't work; right-click the image itself and copy the image address.

Shortcuts are **per-server** — yours don't leak into anyone else's. *3-second cooldown.*

### `/imgur` — a shared album

| Command | Who | What |
|---|---|---|
| `/imgur get` | Anyone | A random image from the server's album |
| `/imgur setlink link:<url>` | **Admin** | Point the bot at an Imgur album |
| `/imgur refresh` | **Admin** | Re-read the album after you've added images |

The link must be an Imgur album or gallery (`imgur.com/a/…` or `imgur.com/gallery/…`).

**Refresh has a 6-hour cooldown.** The album contents are cached, so new images appear
after a refresh rather than instantly — that's what keeps `/imgur get` fast.

---

## 5. Manga reader

### `/manga source:<source> chapter:<n> [series] [group] [page]`

Opens a page-by-page reader inside Discord.

**Sources:**

| Value | Meaning |
|---|---|
| `MD` | MangaDex |
| `G` | Google Drive |
| `V` | Viz (official) |
| `R` | Raw (Japanese) |

**Series:** `BST` Beastars · `BC` Beast Complex · `PG` Paru Graffiti
**Groups:** `HCS` Hot Chocolate Scans · `HG` Hybridgumi

Only `source` and `chapter` are required; the rest have sensible defaults.

**Reader controls:**

| Button | What it does |
|---|---|
| `<<` `<` | First page / back one |
| **Jump** | Type a page number and go straight there |
| `>` `>>` | Forward one / last page |
| **End Session** | Close the reader |

**The reader belongs to whoever opened it.** Someone else pressing the buttons gets a
private *Not Your Session* notice — so two people can read different chapters in the same
channel without fighting over the controls. Sessions expire after a period of inactivity.

Page-turning is **never rate-limited**. Reading is the whole point.

### `/randompage [series] [source] [group]`

A random page from the catalogue. Every argument is optional — leave them all off for a
completely random pull, or narrow it down.

---

## 6. The Leg economy

A Beastars-flavoured trading game. **Every member is born with exactly two legs**, and
once they're given away they're gone.

### `/leg offer member:@someone`

Offers one of your legs. You get a confirmation with your name in the title:

- **Confirm Sacrifice** — the leg changes hands, permanently
- **Cancel** — you keep it

**Only you can confirm your own offer.** Both people appear as clickable mentions, and
nobody gets pinged by it.

Refusals you might see:

| Message | Meaning |
|---|---|
| **Instinct Suppressed** | You can't feed yourself |
| **Inorganic Target** | You can't feed a bot |
| **Flesh Exhausted** | You've already given both legs |
| **Blacklisted** | An admin banned you from the economy |
| **Marked Herd** | You hold a role that's banned from it |
| **Insufficient Standing** | You don't hold a role that's required |
| **Still Settling In** | You're too new — see the wait period below |
| **Economy Closed** | An admin has switched the whole thing off |

### `/leg stats [member]`

An anatomy sheet: legs remaining, legs surrendered, legs consumed, and a title earned from
your record.

| Title | Earned by |
|---|---|
| The Innocent | Nothing yet |
| The Noble Herbivore | Given 1 leg |
| The Red Deer | Given 2 legs |
| The Amputee | Given more than 2 (admin-adjusted) |
| The Awakened Carnivore | Received at least 1 |
| **Apex Predator** | Received 5 or more |

**Interaction History** switches to a log of who fed whom, paginated. **Overview** goes
back.

### `/leg leaderboard`

Ranks members by **legs received**. Top three are marked.

| Control | What it does |
|---|---|
| `<<` `<` `>` `>>` | Paging |
| **Find Me** | Jumps to the page with your row and highlights it |
| **Filter by Role** | Show only members holding roles you pick |
| **Show Everyone** | Clear the filter |

**Filtering** opens a role picker. Filters are **per-message**, so two leaderboards in a
channel filter independently, and ranks stay as **server-wide standing** — someone fourth
overall still reads as #4 inside a filter, because it's one economy viewed through a lens,
not a separate league.

> **You won't appear on the leaderboard until you've received at least one leg.** Giving
> legs away doesn't put you on it — the board ranks by what you've eaten. **Find Me** will
> tell you so rather than jumping somewhere random.

---

## 7. Admin dashboard

```
/admin dashboard
```

Opens the hub. Add `ephemeral:false` to post it publicly instead of privately.

**Who can use it:** anyone with **Administrator**, or holding a role you've marked as a Bot
Admin. Every button re-checks that when clicked, so posting the panel publicly is safe —
other members clicking it get turned away.

### The hub

| Section | Covers |
|---|---|
| **Access Control** | Enable/disable commands, lock them to roles or channels |
| **Manga Settings** | Where the manga reader may be used |
| **Economy** | The Leg economy — everything below |
| **Logging Setup** | Where the audit trail goes |

### Access Control (and Manga Settings)

| Control | What it does |
|---|---|
| **Enable / Disable** | Switch a command off for this server entirely |
| **Role Rules** | Allow-list or deny-list a command by role |
| **Channel Rules** | Restrict a command to certain channels |

A disabled command reports itself as *unavailable* rather than as a permission problem, so
members aren't left thinking they've done something wrong.

### Economy

**Settings row**

| Control | What it does |
|---|---|
| **Enable / Disable Economy** | Master switch |
| **Wait Time** | How many hours a new member must wait before playing (default **24**) |
| **Allowed Roles** | If set, *only* these roles may play |
| **Banned Roles** | These roles may never play |
| **Bypass Roles** | These roles skip the wait period |

The three role lists are **mutually exclusive** — putting a role in one removes it from the
others automatically, because a role that both grants and denies access is unanswerable.

> **Admins can't lock themselves out.** Anyone with Administrator or Manage Server bypasses
> the role gates and the wait period, so a misconfiguration never leaves you unable to fix
> it from inside the bot.

**Member moderation row**

| Control | What it does |
|---|---|
| **Ban** / **Unban** | Bar a member from the economy, or let them back |
| **Edit Stats** | Set someone's leg counts directly |
| **Reset User** | Zero **one member's** statistics |
| **Delete User** | Erase **one member's** profile |

**Reset User** and **Delete User** both act on a *single member*. They are **not** the same
as **Nuke Economy**, which wipes the entire server. Their confirmation forms say so.

> **Reaching someone who has left:** the member picker can only offer people currently in
> the server. Both forms have an **Or a User ID** box underneath for exactly this — paste
> the ID of someone who has left to clean up their leftover profile. Use the picker for
> anyone still present.

**Danger row**

| Control | What it does |
|---|---|
| **Nuke Economy** | Wipes **every** profile on this server. Requires typing `CONFIRM`. |
| **Leaderboard** | Opens the leaderboard visibility page |

### Leaderboard visibility

Controls **who appears on the public ranking**. Nothing here changes anyone's stats, and
every setting is reversible.

| Control | What it does |
|---|---|
| **Hidden Roles** | Members holding these never appear on the leaderboard |
| **Banned: Hidden / Shown** | Whether economy-banned members are listed |
| **Left Server: Hidden / Shown** | Whether people who left are listed |

All three default to **hidden**.

> **Two of these need an extra Discord permission.** *Hidden Roles* and *Left Server*
> require the bot to see the full member list — the **Server Members Intent**. Without it
> the page marks them **"Not in effect"** and explains why, and the leaderboard says so
> too. The **Banned** rule always works, because that's the bot's own record rather than
> Discord's.

### `/admin roles`

Chooses which roles count as **Bot Admin**. Requires native **Administrator** — a Bot Admin
can't edit the Bot Admin list, which would let them entrench their own access.

Slash-only, because it opens a form.

---

## 8. Logging and crash reports

### Audit trail

Every administrative action is recorded — who did it, what, and when.

| Command | What it does |
|---|---|
| `/admin logging view` | Where the audit trail currently goes |
| `/admin logging set channel:#audit` | Post entries to a channel |
| `/admin logging export` | Download the trail as a text file |

**Entries are always written to the database**, whether or not a channel is set. The
channel is a readable copy.

> **With no channel configured, the bot posts a contentless nudge instead of the entry.**
> An audit entry names its target and spells out what was done to them, so publishing it
> into whatever channel an admin happened to be standing in would broadcast moderation
> activity to everyone there.

If the configured channel is deleted, you get warned once — not on every action.

### Crash reports

If something goes wrong, you get a **Process Aborted** message with a reference like
`7f3a91c4`. **Quote that reference when reporting a problem** — it finds the exact incident.

Most of these messages are private and stay until you dismiss them. Two kinds delete
themselves after 15 seconds — a crash in a command that already replied publicly, and a
crash in an `@BeastarsBot` command — and those say so, so you know to copy the reference
first.

*Developer-only commands:* `/admin errorlog view | set | clear` configures where this
server's crashes are reported, and `/admin errorlog health` shows live performance.

---

## 9. Setting up a new server

In order:

1. **Invite the bot** with Send Messages, Embed Links and Attach Files.
2. `/admin roles` — mark which roles are Bot Admins *(needs Administrator)*.
3. `/admin logging set channel:#audit` — so admin actions are recorded somewhere readable.
4. `/admin dashboard` → **Economy** → **Wait Time** — adjust the new-member wait if 24 hours doesn't suit you.
5. `/admin dashboard` → **Economy** → role lists — only if you want to restrict who plays. Leaving **Allowed Roles** empty means everyone.
6. `/imgur setlink link:…` — if you want the album feature.
7. `/image add …` — save a few shortcuts.
8. `/admin dashboard` → **Access Control** — switch off anything you don't want.

Global slash commands can take up to an hour to appear the first time. If a command is
missing right after setup, it hasn't propagated yet.

---

## 10. Limits and defaults

| Thing | Value |
|---|---|
| Legs per member | **2**, for life |
| New-member wait period | **24 hours** (adjustable) |
| Leaderboard page size | 20 |
| Interaction history page size | 8 |
| Interaction history kept per member | most recent 1,000 entries |
| Imgur refresh cooldown | 6 hours |
| Audit trail storage | 5 MB / 10,000 entries, oldest dropped first |
| Audit export size | most recent 500 entries |
| Cooldowns | `/leg` `/image` 3s · `/ping` `/uptime` `/avatar` `/info` `/wiki` `/imgur` `/manga` `/randompage` 5s · `/help` none |
| Page-turning | never rate-limited |

---

## 11. Common questions

**Someone said a command "isn't showing up".**
Global commands take up to an hour to appear after a deploy. After that, check
**Access Control** — it may be disabled, or restricted to roles or channels they don't have.

**Why can't I use the `!` prefix?**
Discord no longer lets the bot read ordinary messages. Use `/command` or
`@BeastarsBot command`.

**Why isn't my role filter on the leaderboard doing anything?**
The bot needs the **Server Members Intent** enabled to know who holds which role. The
admin page will say **"Not in effect"** when this is the problem. That's a bot-operator
setting, not something you can change from Discord.

**I set an image URL and it says the link is invalid.**
It must be a *direct* image link ending in `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg` or
`.webp`. Right-click the image itself and copy the image address, rather than copying the
address of the page it's on.

**Someone left the server and their leg profile is still there.**
Use **Reset User** or **Delete User** and paste their user ID into the **Or a User ID**
box. The member picker can't offer people who have left.

**Can I get legs back?**
Not through normal play — that's the point of the economy. An admin can use **Edit Stats**
or **Reset User**.

**The bot posted an error and the message disappeared.**
Crash notices in public replies delete themselves after 15 seconds and say so. Copy the
reference code before it goes.

---

*Anything not covered here, run `/help` — it's generated from the bot itself, so it can
never fall out of date with what the commands actually accept.*
