# JSON models

Drop a `*.json` file in this folder and restart Core. It is registered as a regular
addon — same protocol, same cache levels, same handlers, same database tables — but
nothing is compiled and no JAR is built. Java addons in `addons/` keep working exactly
as before; the two live side by side and only have to use different `addonId`s and
cache prefixes.

`animals.json.example` is a complete, commented model. Rename it to `animals.json` to
load it. Comments (`//`, `/* */`) and trailing commas are allowed in definition files.

## The smallest possible model

```json
{
  "addonId": 200,
  "name": "Player Stats",
  "fields": {
    "uuid":  { "type": "string", "id": true },
    "kills": { "type": "long" }
  }
}
```

That is the equivalent of a `DataAddon` subclass with two `@DbDataModels` fields.
`GET_DATA`, `SET_DATA`, `INCREMENT_DATA`, `REMOVE_DATA`, `RANKING` and `RANK_FINDER`
work against it immediately, with `protocol: 200`.

## Top level

| Key | Default | Meaning |
| --- | --- | --- |
| `addonId` | **required** | Protocol id; unique across every addon *and* model |
| `name` | file name | Display name on the dashboard (`addonName()`) |
| `cacheKeyHeaderTag` | `name` as a slug | Cache key prefix — keys are `prefix_id` |
| `namespace` | `nexus_core_db` | Mongo database / SQL grouping |
| `dataset` | `name` as a slug | Mongo collection / SQL table |
| `cacheTTL` | `300` | Redis TTL in seconds |
| `l1Cache` | `true` | Use the Caffeine L1 cache |
| `metrics` | disabled | InfluxDB settings (below) |
| `access` | allow everything | Request rules (below) |
| `types` | — | Named types, i.e. inner classes |
| `fields` | **required** | The model's own fields |

Aliases are accepted where they read better: `id`/`protocol` for `addonId`,
`database` for `namespace`, `collection`/`table` for `dataset`, `ttl` for `cacheTTL`.

Exactly one field must carry `"id": true`, and it must be a scalar. Its type decides
what `INCREMENT_DATA`'s `key` is parsed as (`string` → `String`, `long` → `Long`, …).

## Field types

| `type` | Default value | Notes |
| --- | --- | --- |
| `string` | `""` | |
| `int`, `long`, `double` | `0` | |
| `boolean` | `false` | |
| `object` | its type's defaults | Needs `of`/a type name, or an inline `fields` block |
| `list` | `[]` | Element declared with `of` |
| `map` | `{}` | Values declared with `of`, keys with `key` (default `string`) |
| `any` | `null` | Stored as-is, no validation |

A field is either an object spec or, when nothing but the type matters, just the type
name:

```json
"fields": {
  "uuid":   { "type": "string", "id": true },
  "name":   "string",
  "kills":  { "type": "long", "default": 0 },
  "tags":   { "type": "list", "of": "string" },
  "scores": { "type": "map", "key": "string", "of": "int" }
}
```

Field spec keys: `type`, `default`, `id`, `of` (list element / map value), `key`
(map key type), `fields` (inline type), `types` (types local to that field),
`merge` (maps only), `metric`.

## Inner classes

Types declared under `types` behave like inner classes: a type nested in another is
visible by its simple name from inside it, and as `Outer.Inner` from outside. This is
the "birds have their own traits, dogs have their own" case:

```json
"types": {
  "Traits": {
    "types": {
      "Bird": { "canFly": { "type": "boolean", "default": true } },
      "Dog":  { "breed":  { "type": "string",  "default": "unknown" } }
    },
    "fields": {
      "bird": { "type": "Bird" },
      "dog":  { "type": "Dog" }
    }
  }
},
"fields": {
  "uuid":   { "type": "string", "id": true },
  "traits": { "type": "Traits" },
  "pets":   { "type": "list", "of": "Traits.Dog" },
  "byName": { "type": "map",  "of": "Traits.Dog" }
}
```

A type body may be written as `{ "fields": {…}, "types": {…} }` or, as a shorthand,
as the field map itself (as `Bird` and `Dog` are above). A type that contains itself
— directly or through another type — is rejected at load time.

A type used in one place only can be written inline instead of being named:

```json
"meta": {
  "type": "object",
  "fields": {
    "note":  { "type": "string" },
    "level": { "type": "int", "default": 1 }
  }
}
```

## How incoming data is applied

Every stored document has exactly the declared fields: missing ones are filled with
their defaults, undeclared ones are dropped, and values are coerced to the declared
type (`"7"` → `7`). A value that cannot be coerced falls back to the default and is
logged at `FINE`.

- **object** — merged field by field over its defaults, so `{"traits":{"bird":{"wingSpan":2.5}}}`
  leaves `canFly` alone.
- **list** — replaced wholesale; each element is normalized against the element type.
- **map** — replaced wholesale, so entries can be deleted; add `"merge": true` to
  merge entries over the default map instead.

## Metrics

```json
"metrics": { "enabled": true, "measurement": "animals" },
"fields": {
  "kills":  { "type": "long", "metric": true },
  "server": { "type": "string", "metric": { "name": "srv", "tag": true } }
}
```

The counterpart of `@NexusMetricConfig` / `@NexusMetric`. Only top-level scalar fields
can be written to InfluxDB; the id field is always the point's tag.

## Access

```json
"access": {
  "default": "allow",
  "deny":    ["REMOVE_DATA"],
  "sources": { "INCREMENT_DATA": ["lobby", "survival"], "*": ["lobby", "survival", "admin"] }
}
```

This is what a Java addon writes inside `handleRequest`. `deny` wins over `allow`;
listing `allow` without `default` turns the model into a whitelist. `sources` limits
who may send a request type — `"*"` applies to every type. Source names are matched
case-insensitively.

For anything beyond this — custom validation, custom request types, side effects —
write a Java addon; the two mechanisms are independent.

## When a file is wrong

The model is skipped with one log line naming the file and the exact path, for example:

```
[models] animals.json: fields.pets.type: unknown type 'Dogg'; declare it under "types" or use a built-in type
```

Everything else keeps loading.
