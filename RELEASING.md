# Releasing

There are two ways to get the library from a Maven repository.

## JitPack (no setup)

[JitPack](https://jitpack.io/#garywild/haveno-java-client) builds the library from this GitHub repo on demand. Every git tag, such as `v0.1.0`, becomes a version you can depend on. There is nothing to configure: push a tag, and the first person to request that version triggers the build.

## Maven Central (one-time setup)

Maven Central is the default repository, so users of a Central release don't need to add an extra `<repository>`. The workflow in `.github/workflows/release.yml` publishes a release automatically. Before it can, you need to set up an account, a signing key and four secrets. You only do this once.

### 1. Create a Central Portal account and namespace

1. Go to <https://central.sonatype.com> and sign in **with GitHub**, as `garywild`.
   Signing in this way verifies the namespace `io.github.garywild` automatically, and that namespace is this library's `groupId`.
   Check that the namespace shows as verified under **View Namespaces**.
2. Open **View Account**, choose **Generate User Token**, and copy the username and password it shows.

### 2. Create a GPG signing key

Maven Central only accepts signed artifacts.

```
gpg --full-generate-key                    # choose RSA 4096 and set a passphrase
gpg --list-secret-keys --keyid-format=long # note the key ID after "rsa4096/"
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID>  # the whole block, BEGIN to END
```

`keys.openpgp.org` sends a verification email. Click the link in it, so Central can find your public key.

### 3. Add the secrets to GitHub

In this repo, go to **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret | Value |
|---|---|
| `CENTRAL_USERNAME` | The token username from step 1 |
| `CENTRAL_PASSWORD` | The token password from step 1 |
| `GPG_PRIVATE_KEY` | The armored private key from step 2 |
| `GPG_PASSPHRASE` | The key's passphrase |

### 4. Release

On GitHub, go to **Releases → Draft a new release**, create a tag such as `v0.1.0`, and publish the release.

The **release to Maven Central** workflow then sets the version from the tag, signs the artifacts and uploads them. You can follow its progress in the **Actions** tab, and the release shows up under **Deployments** on central.sonatype.com. It typically becomes searchable on Maven Central within an hour.

Versions on Maven Central are permanent: a published version can't be deleted or replaced. To fix a release, publish a new version.
