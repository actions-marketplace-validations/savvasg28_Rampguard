# Contract fixtures

These files are the Android Publisher API responses that the adapter tests replay. Each file reproduces the shape of a
real response: field names, nesting, value types and enum spellings. Every value is synthetic.

`publisher/track-with-extra-fields.json` adds fields that a real track can carry, such as release notes and country
targeting. The write tests use it to prove that those fields survive the round trip.
