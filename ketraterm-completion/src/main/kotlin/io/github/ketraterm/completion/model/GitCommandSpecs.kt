/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ketraterm.completion.model

/**
 * Curated Git CLI specifications for developer workflows.
 */
internal object GitCommandSpecs {
    fun git(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "git",
            description = "distributed version control",
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "status",
                        description = "show working tree status",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--short", "-s"), "show status concisely"),
                                TerminalOptionSpec(listOf("--branch", "-b"), "show branch information"),
                                TerminalOptionSpec(listOf("--ignored"), "show ignored files as well"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "add",
                        description = "add file contents to the index",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-A", "--all"), "add changes from all tracked and untracked files"),
                                TerminalOptionSpec(
                                    listOf("-p", "--patch"),
                                    "interactively choose hunks of patch between the index and the work tree",
                                ),
                                TerminalOptionSpec(
                                    listOf("-u", "--update"),
                                    "update the index just where it already has an entry matching <pathspec>",
                                ),
                                TerminalOptionSpec(listOf("-f", "--force"), "allow adding otherwise ignored files"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "restore",
                        description = "restore working tree files",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--staged", "-S"), "restore the index"),
                                TerminalOptionSpec(listOf("--worktree", "-W"), "restore the working tree"),
                                TerminalOptionSpec(
                                    listOf("--source", "-s"),
                                    "which tree to restore from",
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "rm",
                        description = "remove files from the working tree and index",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--cached"), "only remove from the index"),
                                TerminalOptionSpec(listOf("-r"), "allow recursive removal when a leading directory name is given"),
                                TerminalOptionSpec(listOf("-f", "--force"), "override the up-to-date check"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "commit",
                        description = "record changes to the repository",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-m", "--message"), "commit message", requiresValue = true),
                                TerminalOptionSpec(listOf("-a", "--all"), "stage all modified and deleted files"),
                                TerminalOptionSpec(listOf("--amend"), "amend previous commit"),
                                TerminalOptionSpec(listOf("--no-verify", "-n"), "bypass pre-commit and commit-msg hooks"),
                                TerminalOptionSpec(listOf("--allow-empty"), "allow recording a commit with no changes"),
                                TerminalOptionSpec(
                                    listOf("-s", "--signoff"),
                                    "add Signed-off-by line by the committer at the end of the commit log message",
                                ),
                                TerminalOptionSpec(
                                    listOf("--fixup"),
                                    "construct a fixup commit for use with rebase --autosquash",
                                    requiresValue = true,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "checkout",
                        description = "switch branches or restore files",
                        aliases = listOf("co"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-b"), "create and check out a new branch", requiresValue = true),
                                TerminalOptionSpec(listOf("-B"), "create/reset and check out a branch", requiresValue = true),
                                TerminalOptionSpec(listOf("--detach"), "detach HEAD at named commit"),
                                TerminalOptionSpec(listOf("--theirs"), "check out their version for unmerged files"),
                                TerminalOptionSpec(listOf("--ours"), "check out our version for unmerged files"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "switch",
                        description = "switch branches",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-c", "--create"), "create and switch to a new branch", requiresValue = true),
                                TerminalOptionSpec(
                                    listOf("-C", "--force-create"),
                                    "create/reset and switch to a branch",
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(listOf("-d", "--detach"), "switch to a commit in detached HEAD state"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "branch",
                        description = "list, create, or delete branches",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-a", "--all"), "list both remote-tracking and local branches"),
                                TerminalOptionSpec(listOf("-r", "--remotes"), "list remote-tracking branches"),
                                TerminalOptionSpec(
                                    listOf("-d", "--delete"),
                                    "delete fully merged branch",
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                TerminalOptionSpec(
                                    listOf("-D"),
                                    "force delete branch",
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                TerminalOptionSpec(listOf("-m", "--move"), "move/rename a branch"),
                                TerminalOptionSpec(listOf("--merged"), "print only branches that are merged into HEAD"),
                                TerminalOptionSpec(listOf("--no-merged"), "print only branches that are not merged into HEAD"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "pull",
                        description = "fetch from and integrate with another repository",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-r", "--rebase"), "rebase current branch on top of upstream branch"),
                                TerminalOptionSpec(listOf("--autostash"), "automatically stash and unstash local changes"),
                                TerminalOptionSpec(
                                    listOf("--no-ff"),
                                    "create a merge commit even when the merge could be resolved as a fast-forward",
                                ),
                                TerminalOptionSpec(
                                    listOf("--ff-only"),
                                    "refuse to merge unless the current HEAD is already up to date or the merge can be resolved as a fast-forward",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "push",
                        description = "update remote refs",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-u", "--set-upstream"), "set upstream for git pull/status"),
                                TerminalOptionSpec(listOf("-f", "--force"), "force update"),
                                TerminalOptionSpec(
                                    listOf("--force-with-lease"),
                                    "force update only if remote branch matches expected state",
                                ),
                                TerminalOptionSpec(listOf("--all"), "push all branches"),
                                TerminalOptionSpec(listOf("--tags"), "push all tags"),
                                TerminalOptionSpec(listOf("-d", "--delete"), "delete all listed refs from the remote repository"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "fetch",
                        description = "download objects and refs",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--all"), "fetch all remotes"),
                                TerminalOptionSpec(
                                    listOf("-p", "--prune"),
                                    "remove local tracking branches that no longer exist on remote",
                                ),
                                TerminalOptionSpec(listOf("--tags"), "fetch all tags from the remote"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "merge",
                        description = "join development histories",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("--no-ff"),
                                    "create a merge commit even when the merge could be resolved as a fast-forward",
                                ),
                                TerminalOptionSpec(listOf("--ff-only"), "refuse to merge unless fast-forward"),
                                TerminalOptionSpec(listOf("--squash"), "squash commits into a single commit"),
                                TerminalOptionSpec(listOf("--abort"), "abort current conflict resolution"),
                                TerminalOptionSpec(listOf("--continue"), "continue current merge"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "rebase",
                        description = "reapply commits on top of another base",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-i", "--interactive"),
                                    "make a list of the commits which are about to be rebased",
                                ),
                                TerminalOptionSpec(
                                    listOf("--onto"),
                                    "starting point at which to create the new commits",
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                TerminalOptionSpec(
                                    listOf("--continue"),
                                    "restart the rebasing process after editing a commit or resolving a merge conflict",
                                ),
                                TerminalOptionSpec(listOf("--abort"), "abort the rebase operation and reset HEAD to the original branch"),
                                TerminalOptionSpec(listOf("--skip"), "restart the rebasing process by skipping the current patch"),
                                TerminalOptionSpec(
                                    listOf("--autostash"),
                                    "automatically create a temporary stash entry before the operation begins",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "reset",
                        description = "reset current HEAD to the specified state",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--soft"), "do not touch the index file or the working tree at all"),
                                TerminalOptionSpec(listOf("--mixed"), "resets the index but not the working tree"),
                                TerminalOptionSpec(listOf("--hard"), "resets the index and working tree"),
                                TerminalOptionSpec(
                                    listOf("--merge"),
                                    "resets index and updates files in working tree that differ between commit and HEAD",
                                ),
                                TerminalOptionSpec(
                                    listOf("--keep"),
                                    "resets index, updates working tree if no uncommitted changes in tracked files",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "log",
                        description = "show commit logs",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--oneline"), "shorthand for --pretty=oneline --abbrev-commit"),
                                TerminalOptionSpec(listOf("--graph"), "draw a text-based graphical representation of the commit history"),
                                TerminalOptionSpec(listOf("--stat"), "generate a diffstat"),
                                TerminalOptionSpec(
                                    listOf("-n", "--max-count"),
                                    "limit the number of commits to output",
                                    requiresValue = true,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "show",
                        description = "show one or more objects",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_COMMIT,
                    ),
                    TerminalCommandSpec(
                        name = "diff",
                        description = "show changes between commits, trees, or files",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--staged", "--cached"), "view changes staged for the next commit"),
                                TerminalOptionSpec(listOf("--name-only"), "show only names of changed files"),
                                TerminalOptionSpec(listOf("--name-status"), "show only names and status of changed files"),
                                TerminalOptionSpec(listOf("--stat"), "generate a diffstat"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "stash",
                        description = "stash local modifications",
                        subcommands =
                            listOf(
                                TerminalCommandSpec("push", "save local modifications to a new stash entry"),
                                TerminalCommandSpec("pop", "remove a single stashed state from the stash list and apply it"),
                                TerminalCommandSpec("apply", "like pop, but do not remove the state from the stash list"),
                                TerminalCommandSpec("list", "list the stash entries that you currently have"),
                                TerminalCommandSpec("show", "show the changes recorded in the stash entry as a diff"),
                                TerminalCommandSpec("drop", "remove a single stash entry from the list of stash entries"),
                                TerminalCommandSpec("clear", "remove all the stash entries"),
                                TerminalCommandSpec(
                                    "branch",
                                    "create and check out a new branch starting from the commit at which the stash entry was originally created",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "remote",
                        description = "manage set of tracked repositories",
                        subcommands =
                            listOf(
                                TerminalCommandSpec("add", "adds a remote named <name> for the repository at <url>"),
                                TerminalCommandSpec("rename", "renames the remote named <old> to <new>"),
                                TerminalCommandSpec("remove", "deletes the remote named <name>"),
                                TerminalCommandSpec("get-url", "retrieves the URLs for a remote"),
                                TerminalCommandSpec("set-url", "changes URLs for the remote"),
                                TerminalCommandSpec("show", "gives some information about the remote <name>"),
                                TerminalCommandSpec("prune", "deletes stale references associated with <name>"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "tag",
                        description = "create, list, delete or verify a tag object",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-a", "--annotate"), "make an unsigned, annotated tag object"),
                                TerminalOptionSpec(listOf("-d", "--delete"), "delete tags with given names"),
                                TerminalOptionSpec(listOf("-l", "--list"), "list tags"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "cherry-pick",
                        description = "apply the changes introduced by some existing commits",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_COMMIT,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--continue"), "continue operation in progress"),
                                TerminalOptionSpec(listOf("--abort"), "cancel operation and return to pre-sequence state"),
                                TerminalOptionSpec(listOf("--skip"), "skip current commit and continue with the rest of the sequence"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "revert",
                        description = "revert some existing commits",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_COMMIT,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--continue"), "continue operation in progress"),
                                TerminalOptionSpec(listOf("--abort"), "cancel operation and return to pre-sequence state"),
                                TerminalOptionSpec(listOf("--no-commit", "-n"), "do not automatically commit reverted changes"),
                            ),
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version"), "show version"),
                    TerminalOptionSpec(
                        names = listOf("-C"),
                        description = "run as if git was started in path",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )
}
