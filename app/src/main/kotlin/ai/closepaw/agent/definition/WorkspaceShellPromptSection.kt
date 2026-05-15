package ai.closepaw.agent.definition

import ai.closepaw.agent.AgentExecutionRole

internal val WORKSPACE_SHELL_PROMPT_SECTION =
    """
    ## Workspace Shell

    You have a termux_shell tool that provides a full Linux bash environment.
    Working directory: ~/closepaw/workspace/

    ### termux_shell
    Full Linux bash shell (via Termux). Supports pipe, redirect, all GNU coreutils.
    Available toolchain: python3, node/npm, git, gcc, cargo, go, etc. (depends on installed packages).
    Working directory: ~/closepaw/workspace/. Input and output files go in this directory.
    To share with other apps, cp to /sdcard/Download/.

    ### When to use which shell
    - termux_shell: when you need a full toolchain (python/git/node, etc.) or pipe/redirect
    - shell: quick device file checks (ls/cat/stat), when you don't need the Termux toolchain

    ### When to use UI tools vs shell
    - UI tools (mobile_action, etc.): phone app interactions, screen navigation
    - termux_shell: files/commands/git/build/scripts
    - Combined: scrape data via browser UI → process with termux_shell. Email attachment → analyze with python.

    ### Guidelines
    - Do not use termux_shell to control Android UI or bypass app restrictions.
    - Input and output files go in ~/closepaw/workspace/.
    """.trimIndent()

private const val MAIN_WORKSPACE_SHELL_DIRECTIVE =
    "For workspace commands (termux_shell), execute directly instead of delegating."

internal fun workspaceShellPromptSectionFor(role: AgentExecutionRole): String {
    if (role != AgentExecutionRole.MAIN) return WORKSPACE_SHELL_PROMPT_SECTION

    return WORKSPACE_SHELL_PROMPT_SECTION + "\n\n" + MAIN_WORKSPACE_SHELL_DIRECTIVE
}
