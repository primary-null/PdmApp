# Publication checklist

Treat every upload as public-facing material, including private repositories.

1. Review the exact files, filenames, comments, fixtures, embedded metadata, and image pixels. Use synthetic names, reserved example domains and documentation-range addresses. Describe layouts and features without listing the author's hardware or capture environment. Keep workstation notes, device inventories, credentials, logs, audit receipts, and recovery copies outside Git.
2. Scan the publication tree with `python tools/check_public_export.py`. Supply `--private-patterns` with an external JSON array of known private identifiers for the local review. Keep that array outside the repository. Inspect all findings; preserve required upstream copyright and license notices.
3. Run the build checks in [DEVELOPMENT.md](DEVELOPMENT.md). Stage only the reviewed source and assets. Inspect the complete staged diff and file list before committing.
4. Credit the sole author using the approved GitHub username and noreply email, `primary-null <285952344+primary-null@users.noreply.github.com>`, for this publication history. Review author, committer, timestamps, messages, trailers, branches, tags, and other refs. Run `python tools/check_public_export.py --history` and `gitleaks git . --redact --no-banner --log-opts="--all"` against the exact history to be uploaded.
5. Inspect the destination repository's visibility, collaborators, descriptions, branches, tags, PRs, issues, releases, artifacts, Pages, and other published surfaces. Upload only the reviewed branch. Verify from a fresh clone and confirm the exact commit and file tree.
6. Keep the repository private until the owner explicitly authorizes a visibility change after a new privacy review. Repository ownership and account activity remain visible to people who can access the repository; the author credit uses the approved GitHub username.

Automated scans are review aids. They do not prove anonymity, inspect every image semantically, erase service caches, or clean other clones. If an identifier has already been uploaded, treat its removal from current files and its removal from accessible history as separate checks. See [GitHub's sensitive-data removal guidance](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).
