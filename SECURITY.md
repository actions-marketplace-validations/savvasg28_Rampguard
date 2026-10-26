# Security

## Report a vulnerability

Email savvasg28@googlemail.com with a description, the affected version and steps to reproduce. Do not open a public
issue for a security problem. You will get an acknowledgement within three working days and a fix or a mitigation
plan within thirty.

## What Rampguard can touch

Rampguard runs inside your GitHub Actions workflow with two secrets:

| Secret | What it grants | How to limit it |
|---|---|---|
| Play service-account key | Reading the production track; changing the rollout fraction, halting and completing the live release. | Give the service account access to this app only, with *View app information* and *Release to production*. Never grant it account-level permissions. |
| Slack webhook URL | Posting messages to one channel. | Create the webhook for a channel that is fine with release notices. |
| Webhook URL, secret and header values (optional) | Posting release events to your own endpoint, or to a vendor API through a header such as an API key. | Use a key scoped to writing events or annotations only. Verify the `X-Rampguard-Signature` header at the receiver. |

The action writes the key to a mode-600 file in the runner's temporary directory for the duration of the run and
deletes it afterwards. The key, the webhook URLs, the webhook secret and header values never appear in logs, the job
summary, exceptions or test output. Tests assert this for each webhook.

## Who can halt a rollout

Anyone whose token can send `repository_dispatch` to the app repository can halt the live release. That is the trust
boundary. Give each sender a fine-grained token limited to that repository, and revoke it when the sender goes away.
Rampguard treats `reason` and `source` as untrusted text: it removes control characters, collapses whitespace and
truncates them, and never interpolates them into a shell command.

## What Rampguard never does

- Write to Play during a dry run. The runner skips the write and a second guard refuses it.
- Write to a halted or completed release.
- Resume a halted release. That is a human decision in the Play Console.
- Run anything but the released jar whose SHA-256 is pinned in `action.yml`.

## Supported versions

Only the latest release receives fixes.
