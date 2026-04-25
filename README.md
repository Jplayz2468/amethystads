# amethystads

## Notes for AI agents

If you are an AI agent working on this repo:

- **Always deploy changes to the existing Cloud Run functions** using the `gcloud` command (do not create new services). The two functions are `edge-node` (public, in `cloud/`) and the admin panel (in `cloud-admin/`). Update them in place with `gcloud run deploy <service-name> ...`.
- **Always compile the plugin when you are done with a change** by running `compile.bat`. Do not consider a plugin change finished until it compiles cleanly.
- **Always commit and push after making a change.** Once a change is complete (and compiled / deployed where applicable), `git add` the relevant files, create a commit with a clear message, and `git push` to `origin`.
