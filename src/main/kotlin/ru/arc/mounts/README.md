# ARC mounts

Native production replacement for `Denizen/scripts/activities/rideable_mobs.dsc`.

## Player behavior

- `/mount` opens a paginated collection of owned mounts with walking, flying
  and swimming filters. Left click summons an owned mount; right click opens
  its card with separate level and settings buttons. Locked mounts never appear
  in the main collection, and a direct locked view returns to that collection.
- Walking mounts use WASD, automatically step over one-block terrain and use
  Space to jump. Horses retain native ridden physics so gravity and terrain
  transitions remain correct; hold and release Space for their charged jump.
  Ordinary flying and swimming mounts use WASD, Space to ascend and Shift to descend.
  Skycruiser uses ordinary player flight, with Space ascent and Shift descent.
  Its Phantom is the player's native passenger, a winged companion above the
  pilot. There is no Happy Ghast carrier or teleported follower, and no guest seats.
  Every mount uses double Shift to dismount; a single Shift never ends the ride.
- Passenger capacity is an inherent catalog feature and needs no ownership node.
  The configured `passenger-seats` count excludes the driver: Camel and Camel
  Husk provide one extra seat, while Ravager and Happy Ghast provide two and
  Polar Bear provides one. Passengers board with the mount's right-click action,
  leave with one Shift, and are cleaned up when the driver session ends.
  Large non-native mounts prepare one invisible, silent Camel carrier per guest
  seat during summon, with both carriers mounted on the root beside the direct
  root driver. Preparation takes about three seconds; guests should retry after
  the preparation message. A single carrier uses root yaw plus 180 degrees to
  place the guest behind the driver. With two seats, the first uses root yaw plus
  `passengers.carrier-yaw-offset`; the second uses root yaw minus it. Tune
  carrier size with `passengers.carrier-scale`.
- Typed per-mount abilities are configured under `abilities`. The mountain
  goat, frog, horse and fox have authored jump strengths. Contextual permanent
  upgrades are bought from the dedicated settings screen: night vision also fits the
  skeleton and Enderman, water breathing supports aquatic mounts, fire
  resistance protects Nether mounts, and dolphin grace accelerates the
  dolphin. Effects are refreshed while riding and expire naturally after
  dismount without removing unrelated player effects.
- Every mount has three progression levels. The third level is the deliberately
  expensive final sprint and improves speed, steering and sprint response. An
  optional per-level `scale` multiplies the selected base or skin appearance;
  omitted values remain `1.0` for backward-compatible visuals.
- Every mount exposes an ordinary size plus separately owned comic extremes. A
  selected size remains a free setting, but `grant-only` sizes require a
  separate ownership node through administration, rewards, or their configured
  permanent purchase. Authored intermediate profiles may still use level gates.
- Mount speed is controller-owned and ramps independently of vanilla entity
  friction. `movement.acceleration-time`, `deceleration-time` and `turn-time`
  set global response times; `0s` restores instant response. A mount may
  override any of them under `mounts.<id>.motion`. Opposite input brakes close
  to zero before the new direction accelerates, and horses keep native riding
  while ARC ramps their movement-speed attribute.
- All bundled ARC motion timings are zero. Native client flight acceleration and
  network prediction remain part of Minecraft's flight controller.
- Appearance is deterministic. ARC fixes age, scale and variants, clears random
  entity equipment, then applies only the configured skin equipment. Zombie
  baby, iron guard and diamond warlord are separate unlockable skins.
- Cosmetic trails use the live scaled bounding box and movement direction to
  emit behind the body instead of at the entity origin. Their localized name,
  cadence, density, rear offset, height, spread and speed are catalog data.
- Settings are reconciled into an active ride as one immutable snapshot after
  LuckPerms persistence succeeds. Speed, step height, glow, skin, trail,
  abilities and safe size changes therefore take effect without resummoning.
  If a larger hitbox would intersect blocks, the saved size is deferred until
  the next safe summon instead of partially mutating the current ride.
- Typed `behaviors` are separate from purchasable potion abilities. The
  Ravager's `ram` is requested by a fresh sprint-forward press, waits for the
  mount to accelerate, then attempts one standard player-attributed hit on the
  first hostile mob in its swept path. It never targets players or passive
  mobs, never damages blocks, never bypasses a cancelled damage event and has a
  four-second cooldown even when the hit misses or is denied.
- The temporary vehicle entity is invulnerable and absorbs damage without
  ending the ride. Ordinary rider damage still applies; one hit whose final
  post-reduction damage reaches `safety.rider-knockoff-damage` dismisses the
  mount on the next tick. Rider suffocation inside a block remains cancelled as
  a mount hitbox safety measure.
- Rider death, logout, teleport, world change, expiry, idle timeout, invalid
  state, world-border/height escape, or genuinely leaving water removes the
  temporary entity. Kelp, seagrass and bubble columns remain valid aquatic
  environments.
- A short summon cooldown protects against duplicate entities. ARC-controlled
  vehicles have a server velocity cap; native flight uses a bounded nominal
  speed attribute rather than a per-tick velocity clamp.
- Native phasing entities such as the Vex use a swept Paper collision check
  before ARC applies velocity. Blocked axes stop while free axes keep moving,
  so the mount slides along a wall instead of carrying its rider through it.

## Permissions and commands

All access, ownership and settings use only `arc.mounts.*`:

- `arc.mounts.use` — open and use `/mount`;
- `arc.mounts.<mount>.<level>` — owned progression level;
- `arc.mounts.<mount>.glow` and `.glow.disabled` — glow ownership and setting;
- `arc.mounts.<mount>.skin.<skin>` — skin ownership;
- `arc.mounts.<mount>.skin.active.<skin>` — selected skin marker;
- `arc.mounts.<mount>.ability.<ability>` — permanent contextual ability;
- `arc.mounts.<mount>.size.<size>` — ownership of a grant-only extreme size;
- `arc.mounts.<mount>.tuning.speed.<percentage>` — selected speed profile;
- `arc.mounts.<mount>.tuning.step-height.<hundredths>` — selected step height;
- `arc.mounts.<mount>.tuning.size.<size>` — selected authored size profile;
- `arc.mounts.admin` — all `/mount admin ...` operations.

The command surface is deliberately unified:

- `/mount admin summon <mount> [level] [skin]` — short test ride using the
  selected configured level, never an arbitrary raw speed;
- `/mount admin grant <level|skin|glow|ability|size> <player> <mount> [value]` — grant an
  exact ownership node;
- `/mount admin revoke <level|skin|glow|ability|size> <player> <mount> [value]` — revoke an
  exact ownership node and any dependent active skin/glow setting.

`/mount admin summon zombie 3 baby` is the administrator smoke command. No
legacy `/ride-mob`, `/unlock-mount`, `arc.mounts.ride` or `mcfine.*` mount
permission is registered or consulted.

## Spawn and protection integration

ARC tags the entity inside Paper's spawn initializer and generates a one-time
in-memory token before `CreatureSpawnEvent`. It uncancels only a cancelled
`CUSTOM` event whose owner UUID, catalog ID and token all match the currently
pending ARC summon. This lets mounts work at spawn while ordinary WorldGuard
mob-spawn restrictions remain intact.

## Economy safety

New mount purchases and paid upgrades are available only while the player is
near a live spawn merchant (Citizens NPC 368 or 369) in `rc_origin_spawn` and
the node has `purchases-enabled`. The shop command outside that context falls
back to the owned collection. The coordinator repeats this check immediately
before creating the purchase journal, so moving away, changing worlds,
despawning the NPC or losing Citizens fails closed before any debit.

Purchases are enabled only on the spawn node. Prices are converted to exact
minor currency units and charged directly through the RedisEconomy 4.5.12 API.
Levels use the mount wallet, while glow, skins, abilities and paid extreme sizes
select their configured wallet independently. Existing ownership survives a
currency or price change; selecting an owned improvement never charges again.
Historical provider doubles may contain a sub-cent binary tail; ARC accepts
only a tiny drift within 0.05 of one minor unit, while real fractional-cent
balances still fail closed. The adapter also refuses non-zero provider tax or a
changed pre-call balance.

Before any withdrawal, ARC atomically writes
`plugins/ARC/data/mount-purchases.json`. The journal records the intended
permission and exact before/after balance evidence. Permission failure triggers
one exact compensating refund. On restart ARC recovers a proven withdrawal by
checking the direct LuckPerms node and, for an ambiguous provider call, matching
the transaction UUID, amount, currency and timestamp in RedisEconomy history.
Unprovable outcomes fail closed in `MANUAL_REVIEW`, block duplicate purchases
for that player and are exposed through logs and bounded Prometheus metrics.
ARC rechecks those records on every start and automatically resumes only when
the exact debit, refund or direct permission can be proven.

## Configuration and observability

The bundled and live file is `plugins/ARC/modules/mounts.yml`. It owns the
catalog, rarity, descriptions, three level price/speed/handling/scale values,
deterministic base appearance, skins, equipment, authored size profiles,
behaviors, passenger seat capacity and cosmetic trail geometry. Spawn, survival
and parkour keep separate tracked copies for their world and purchasing
policies.

Metrics use no player or transaction labels:

- `arc_mounts_enabled`, `arc_mounts_purchases_enabled`;
- `arc_mounts_catalog_entries`, `arc_mounts_active_sessions`;
- `arc_mount_purchase_journal_unresolved`;
- `arc_mount_purchase_journal_records{status=...}`.

The package is self-contained behind `MountOwnership`, `MountWallet` and
`MountPurchaseJournal`; `MountModule` is its only ARC lifecycle entry point.

Pricing follows the mandatory ops economy inventory and the dated 2026-09-07 assessment. Flying entry prices have a120-token floor without changing cosmetic rarity; reward-only unlocks stay reward-only. New shared skins provide WAVE, RIBBON, COMET and HEART trails, each at most60particles/second per mount. Cosmetics do not grant income or movement bonuses.
