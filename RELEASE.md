# Releasing kotlin-type-mapper

## Prerequisites

Before your first release, configure these secrets in the GitHub repository settings (`Settings → Secrets and variables → Actions`):

| Secret | Description |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username (generate at [central.sonatype.com](https://central.sonatype.com) → Account → User Token) |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `GPG_SIGNING_KEY` | Full armored PGP private key (`gpg --export-secret-keys --armor <key-id>`) |
| `GPG_SIGNING_PASSWORD` | GPG passphrase |

Also allow the release workflow to push the version back to `main` after publishing. In the branch protection settings for `main`:
- **Classic branch protection**: enable *Allow specified actors to bypass required pull requests* and add `github-actions[bot]`
- **Rulesets** (newer): add `github-actions[bot]` to the bypass list

## Steps to release

1. **Create a GitHub release** via the GitHub GUI:
   - Go to the repo → *Releases* → *Draft a new release*
   - Create a new tag (e.g. `0.4.0`) pointing at `main`
   - Fill in the release notes and click **Publish release**
2. The [Release workflow](.github/workflows/release.yml) triggers automatically:
   - Uses the release tag as the version — no need to update `gradle.properties` first
   - Builds and signs `model`, `analyzer`, and `cli` JARs
   - Bumps `gradle.properties` to the next patch `-SNAPSHOT` version on `main` (e.g. `0.4.0` → `0.4.1-SNAPSHOT`)
   - Uploads the deployment to [Maven Central Portal](https://central.sonatype.com/publishing/deployments)
   - Attaches all JARs to the GitHub release
3. **Approve on Maven Central** — go to [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments) and click **Publish** on the deployment (or **Drop** to abort)

> **Note:** Step 3 is intentionally manual. Maven Central releases are irreversible — always verify the deployment looks correct before publishing.

## Snapshot releases

Trigger the workflow manually via *Actions → Release to Maven Central → Run workflow* and enter a version ending in `-SNAPSHOT` (e.g. `0.4.0-SNAPSHOT`). Snapshots are published automatically without a manual approval step and are available at the [Central Portal snapshot repository](https://central.sonatype.com/repository/maven-snapshots/).

