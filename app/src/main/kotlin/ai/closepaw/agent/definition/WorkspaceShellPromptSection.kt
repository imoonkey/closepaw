package ai.closepaw.agent.definition

internal val WORKSPACE_SHELL_PROMPT_SECTION =
    """
    ## Workspace Shell

    You have a termux_shell tool that provides a full Linux bash environment, separate from the Android toybox shell.

    - termux_shell: full bash, supports pipes/redirects/process substitution, has python3, git, ripgrep available. Use this for running scripts, manipulating files, version control, etc.
    - shell: Android toybox shell. Limited commands, no pipes. Use only for inspecting Android device state when termux_shell is overkill.
    - Workspace files live in ~/closepaw/workspace/. Use ~/.closepaw/artifacts/ to share large files between commands.
    - For UI manipulation, prefer mobile_action / system_button / open_app — termux_shell cannot drive the UI.
    """.trimIndent()
