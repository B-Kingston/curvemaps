# Contributing to CurveMaps

Thanks for helping. CurveMaps is source-available under the PolyForm Shield
License 1.0.0 (see `LICENSE`): you may read, build, change and use it for any
purpose except providing a product that competes with CurveMaps. The official
app is published by the project owner.

## How changes land

The public repository, `B-Kingston/curvemaps`, is published from the owner's
development tree. Pull requests are welcome there. An accepted pull request is
applied upstream with you as its author, then comes back to the public
repository with the next sync. The pull request itself is closed rather than
merged.

## Contributor licence agreement (required)

Before a pull request can be accepted, you must agree to the
[CurveMaps Contributor Licence Agreement](CLA.md). Add this line to your pull
request description:

> I have read and agree to the CurveMaps Contributor Licence Agreement (CLA.md),
> and my contribution is submitted under it.

**Why:** you keep the copyright in your contribution. The agreement lets the
project owner ship it in the official app, under app-store terms that the
PolyForm Shield licence alone doesn't grant.

## How to contribute

- Read `AGENTS.md` first. It records the build, the data pipelines, the test
  regime, and the constraints that were learned the expensive way.
- Every change needs tests at the rigour `AGENTS.md` describes. Run
  `tools/test/ci.sh` before opening a pull request.
- Keep the app offline-first. Network code belongs only in
  `app/src/main/java/com/curvemaps/app/region/net/`.
- Don't commit generated map data, keystores or credentials.

## Name and logo

The licence covers the code, not the CurveMaps name or logo. See
[TRADEMARKS.md](TRADEMARKS.md).
