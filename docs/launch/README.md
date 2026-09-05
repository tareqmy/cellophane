# Launch checklist

Everything here is for the person launching Cellophane; none of it runs automatically. Posts are drafts in the
files next to this one. Work top to bottom.

## 1. Publish the repository

The repository exists privately with `master` pushed. Make it public and give it a description and topics:

```bash
gh repo edit tareqmy/cellophane --visibility public --accept-visibility-change-consequences
gh repo edit tareqmy/cellophane \
  --description "Mailpit for SMS: a fake SMPP operator with a live inbox, decoded PDUs and chaos rules" \
  --homepage "https://github.com/tareqmy/cellophane#readme" \
  --add-topic smpp --add-topic sms --add-topic smsc --add-topic simulator --add-topic testing \
  --add-topic java --add-topic spring-boot --add-topic netty --add-topic docker --add-topic developer-tools
```

Check that the **build** workflow goes green on all three operating systems before tagging. Windows is the
one most likely to surprise.

## 2. Cut the release

A `v0.1.0` tag is already on GitHub, but it points at the pre-Maven commit and, because it arrived in the
repository's first push, it never triggered a workflow. The local tag has been moved to the launch-ready
commit; push it over the old one:

```bash
git push --force origin v0.1.0
```

Pushing a tag runs the **publish** workflow: tests, container image, smoke test against the image, then a push to
`ghcr.io/tareqmy/cellophane:0.1.0` and `:latest`. Afterwards:

```bash
gh release create v0.1.0 --title "Cellophane 0.1.0" --notes-file docs/launch/release-notes.md
```

Then make the package public: GitHub → your profile → Packages → cellophane → Package settings → Change
visibility. GHCR packages start private; the README's `docker run` line only works once this is done.

Try it exactly as a stranger would, on a machine without the source:

```bash
docker run -p 2775:2775 -p 8025:8025 ghcr.io/tareqmy/cellophane
```

## 3. Docker Hub (optional)

The publish workflow also pushes to Docker Hub when the repository has `DOCKERHUB_USERNAME` and
`DOCKERHUB_TOKEN` secrets. Create the `tareqmy/cellophane` repository on Docker Hub first and paste
`dockerhub.md` as its description.

## 4. Announce

In this order, a day apart works well:

| Where | Draft | Notes |
|---|---|---|
| Show HN | `show-hn.md` | Weekday, 8-10am US Eastern. Reply to every comment for the first few hours. |
| r/java | `reddit.md` | Text post; the sub dislikes link-only posts. |
| r/telecom | `reddit.md` | Second draft in the file; lead with the operator angle, not Java. |
| LinkedIn | `linkedin.md` | Attach `docs/screenshot-inbox.png`. |
| smpp.org listing | `smpp-org.md` | Email to the address on https://smpp.org/smpp-testing-development.html |
| Jasmin / Kannel communities | `communities.md` | Jasmin Discord and GitHub Discussions; Kannel users list. |

## 5. The first month

Respond to every issue within a day, even with "looking". Early responsiveness converts stars into
contributors. Label issues with `operator-quirk` when someone reports a real-world behaviour worth simulating;
those are the best roadmap input this project can get.
