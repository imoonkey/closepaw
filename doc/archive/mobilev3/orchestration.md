# Mobile-Agent-v3 Orchestration Logic & Adaptation Guide

## 1. Overview of Mobile-Agent-v3 Logic

Mobile-Agent-v3 utilizes a multi-agent modular architecture to handle complex mobile tasks. The core orchestration involves coordinating specialized agents that share a common state (`InfoPool`).

### Key Components

1.  **InfoPool (State Manager)**
    *   Maintains the global state: Instruction, History (Actions, Summaries, Outcomes), Current Plan, Subgoals, and Important Notes.
    *   Tracks "Error Flags" to trigger re-planning if execution gets stuck.

2.  **Manager Application (The Planner)**
    *   **Role:** Decomposes the user instruction into a high-level plan (sequence of subgoals).
    *   **Input:** User instruction, current screenshot, execution history, and current plan status.
    *   **Output:** A structured plan (list of subgoals) and "Thought" reasoning. It can update the plan dynamically based on progress or errors.

3.  **Executor / Operator (The Doer)**
    *   **Role:** Determines the immediate atomic action to execute for the current subgoal.
    *   **Input:** Instruction, current plan/subgoal, history, and screenshot.
    *   **Output:** A specific action (JSON) and description.
    *   **Actions:** `click(x,y)`, `swipe(x1,y1,x2,y2)`, `type(text)`, `long_press(x,y)`, `system_button(back/home)`, `answer(text)`.
    *   **Note:** V3 heavily relies on coordinates derived from the Vision-Language Model (GUIOwl/AutoGLM).

4.  **ActionReflector (The Verifier)**
    *   **Role:** Validates if the last action was successful by comparing pre- and post-action states.
    *   **Input:** Pre-action screenshot, Post-action screenshot, Last action details.
    *   **Output:** Outcome Status:
        *   `A`: Success.
        *   `B`: Failed (Wrong page, need to backtrack).
        *   `C`: Failed (No change).
    *   **Logic:** Updates the `InfoPool` with the outcome, effectively creating a feedback loop.

5.  **Notetaker (The Memory)**
    *   **Role:** Extracts strictly relevant information (text/visual) from the screen to aid future steps.
    *   **Input:** Screenshot, valid instruction context.
    *   **Output:** "Important Notes" string stored in `InfoPool`.

### The Orchestration Loop

1.  **Initialize**: Setup `InfoPool` with the user instruction.
2.  **Manager Phase**:
    *   Observe current state (Screenshot).
    *   Generate or Update Plan. If "Finished", exit.
3.  **Executor Phase**:
    *   Observe current state.
    *   Select one Atomic Action (e.g., `click`).
4.  **Execution**:
    *   Run ADB command (tap, swipe, etc.).
5.  **Reflector Phase**:
    *   Compare state before and after action.
    *   Classify outcome (A/B/C).
6.  **Notetaker Phase** (Optional, typically on Success):
    *   Extract valuable info from the new state.
7.  **Loop**: Repeat from Step 2 (or 3 depending on error state).

---

## 2. Adoption Strategy: Accessibility & OpenAI

You can adopt this orchestration logic for your agent by swapping the **Observation Space** (Screenshot -> Accessibility Tree) and the **Inference Engine** (AutoGLM -> OpenAI).

### A. Replacing GUI Screenshots with Accessibility

In Mobile-Agent-v3, the primary input to agents is the **Screenshot**. We will replace this with a **Textual Representation of the Accessibility Tree**.

#### 1. Input Representation
Instead of passing an image to a VLM, pass a structured text to the LLM.
*   **Raw Data:** Fetch Accessibility Node info (Node tree with resource-id, hierarchy, content-desc, text, bounds, clickable flags).
*   **LLM Prompt Context:**
    ```text
    [Screen Context]
    1. <Button id="com.example:id/login" text="Log In" bounds="[100,200][300,400]" />
    2. <EditText id="com.example:id/username" text="" hint="Username" ... />
    ...
    ```
    *Tip: Filter out non-visible or non-interactable nodes to reduce token usage.*

#### 2. Action Mapping (Coordinates vs. Elements)
V3 agents output `coordinate: [x, y]`.
*   **Your Adaptation:**
    *   **Option A (Recommended):** Semantic selection.
        *   LLM Action: `{"action": "click", "element_id": "1"}` (referencing the index in your context list).
        *   **Controller Logic:** Lookup element #1, parse `bounds="[x1,y1][x2,y2]"`, calculate center `(cx, cy)`, and perform `adb shell input tap cx cy`.
    *   **Option B:** Coordinate hallucination (Not recommended for text-only).
        *   Don't ask a text model to guess coordinates unless you provide center points in the text description explicitly.

### B. Replacing AutoGLM with OpenAI

V3 uses `GUIOwlWrapper` which formats messages for a specific Multi-Modal model.

#### 1. Wrapper Implementation
Create an `OpenAIWrapper` that implements the `predict` interface.
*   **Method:** `predict(text_prompt)`
*   **Implementation:** Use `openai.chat.completions.create` with `model="gpt-4o"` (or `gpt-3.5-turbo` / `gpt-4-turbo` if cost is a concern).
*   **Inputs:** Construct the `messages` array.
    *   `System`: "You are an Android Agent..."
    *   `User`: Combined text prompt from the Manager/Executor + The Accessibility Tree representation.

#### 2. Prompt Adjustments
*   **Manager:** Change "The screenshot displays..." to "The following list describes the current screen elements...".
*   **Executor:** Remove requests for coordinates in the prompt description. Ask for `element_index` or `id`.
*   **Reflector:** Instead of comparing two images, compare the **State Representation**.
    *   *Diffing Logic:* "Compare Screen State A and Screen State B. Did the expected navigation happen?"
    *   *Simpler Heuristic:* Did the list of elements change significantly? Did a specific "success" element appear?
*   **Notetaker:** Just read the text attributes from the accessibility nodes.

### C. Adaptation Summary Table

| Component | Mobile-Agent-v3 (Original) | Your Adapted Agent |
| :--- | :--- | :--- |
| **Observation** | Visual (Screenshots) | Structure/Text (Accessibility Tree) |
| **Model** | AutoGLM / GUIOwl (VLM) | OpenAI GPT-4o / Turbo (LLM) |
| **Planner** | `Manager` (Vision-based) | `Manager` (Context-based) |
| **Action Space** | `click(x,y)`, `swipe` | `click(element_id)`, `swipe(id_start, id_end)` or `swipe(direction)` |
| **Verification** | `ActionReflector` (Visual Diff) | `ActionReflector` (Structure/Text Diff) |
| **Memory** | `Notetaker` (Visual extraction) | `Notetaker` (Text parsing) |

### D. Implementation Steps for You

1.  **State Fetcher:** Implement a function to dump the Accessibility Node tree (using `adb shell dumpsys activity top` or `accessibility` service). Parse it into a clean list.
2.  **LLM Client:** Setup the OpenAI client.
3.  **Port Agents:**
    *   Copy the `mobile_agent_e.py` logic but modify the `get_prompt` methods.
    *   Update `InfoPool` to store text state instead of image paths.
4.  **Controller:** wrapper `adb` commands to translate `element_id` -> `coordinates` -> `tap`.
