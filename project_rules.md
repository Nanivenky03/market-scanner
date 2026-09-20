MITHRON — Development Rules (RULES.md)
This file governs how any AI or human coding agent—Glean, Codex, an architect agent, or another development assistant—reviews, designs, writes, tests, or modifies code in this repository.
These rules are hard constraints, not style suggestions. If a request conflicts with this file, stop and flag the conflict rather than silently choosing one side.
Mithron is a personal automated intraday trading system. It is not an enterprise platform. Every rule exists to serve one goal:
Small, correct, testable, and understandable—in that order of priority above cleverness or scale.
0. Priority order when rules conflict
Correctness > Safety (fail-closed) > Reliability > Simplicity > Performance > Brevity
Never trade correctness or safety for fewer lines of code. When two implementations are equally correct, safe, and reliable, choose the simpler, shorter implementation.
1. The single rule above everything else
Do not write code merely because you can.
First determine whether the requirement can be met by:
Existing code
An existing service, engine, repository, model, or utility
A mature library already in the project
A configuration change
A smaller change than the one being considered
The goal is the smallest reliable, safe, testable implementation that correctly satisfies the requirement. Do not maximize abstraction, cleverness, or code-quality metrics.
2. Never imagine existing code or project behavior
This is a hard rule.
Never invent, assume, reconstruct, or guess the contents of an existing source file, test, migration, configuration file, entity, repository, API, build file, or deployed artifact.
Never infer an existing method signature, constructor, dependency, table name, column name, enum value, repository method, or call path when the actual file or reliable output is not available.
Never make a coding or architectural decision based on imagined existing logic.
Before changing code, inspect the actual relevant files, tests, configuration, migrations, and call paths.
If the complete relevant file is already available in the current working context or sandbox, use it and do not ask for it again.
If the relevant file is not available, ask the user or their coding agent to provide the exact file or exact read-only inspection output before writing or deciding changes.
If only a partial file is available, explicitly identify what is missing and do not guess the missing parts.
If source and deployed artifacts cannot be matched, treat behavior as unverified and say so.
“It probably works this way” is not evidence.
When blocked by missing evidence, provide a precise request such as:
Please provide the complete file:
src/main/java/.../ClassName.java

Also provide the complete affected test file and any directly called class needed to verify the change.

3. Fail-closed is non-negotiable
Mithron places real orders with real money. Missed trade > incorrect trade. When in doubt, do nothing.
Trade execution must halt—not guess, fall back, or continue with a reasonable default—whenever any of the following is true:
Market data is stale or missing.
A required indicator or context flag is not RECONCILED or COMPLETE.
A calculation input is invalid, including NaN, zero-range candle, or missing volume baseline.
Market state, session boundary, or trading-calendar lookup is ambiguous or unknown.
SmartAPI authentication fails.
A provider response is malformed or unexpected.
The system cannot positively confirm whether it is in SIMULATION or LIVE mode.
There is no “log a warning and continue with a best guess” path in order-decision or order-execution code.
4. Minimal implementation
MUST:
Write the smallest amount of code that fully and correctly satisfies the requirement.
Prefer direct, inlined logic over helper methods used exactly once.
Prefer JDK and Spring Boot standard-library behavior over hand-rolled utilities.
Keep one class or method responsible for one clear thing.
MUST NOT:
Add an interface with one implementation merely for future flexibility.
Add configuration options, strategy patterns, or abstraction layers for use cases that do not exist.
Add defensive code without a concrete failure mode it protects against.
Write code because “we might need this later.”
Minimal does not mean clever or cryptic. Code must remain clear, safe, testable, and maintainable.
5. Reuse before creating
Before writing anything new:
Search the repository for existing functionality that solves the problem.
Check existing engines, services, utilities, constants, models, repositories, and configuration.
Reuse or extend an existing abstraction when the behavior genuinely belongs there.
Create something new only when reuse is genuinely not possible, and state why.
Never duplicate existing logic without explaining the reason in the change summary.
6. Single source of truth for shared market facts
A shared market fact is calculated once and reused everywhere:
Market Data → Shared Engine → Canonical Fact → Strategy A / B / C

This applies to:
Candles
RSI, ATR, VWAP, and all indicators
Volume baselines
Market state
Morning reference data
Support and resistance
Option-chain-derived facts
If an engine already produces a fact, strategies consume that output. Strategies must not rebuild it independently.
7. Strategies contain decision logic only
A strategy takes canonical shared facts as input and produces a signal or decision as output.
A strategy must not:
Fetch raw market data.
Manage SmartAPI sessions.
Build candles.
Calculate indicators.
Write arbitrary database records.
Perform order management.
Duplicate another engine’s calculation.
This keeps strategies small and independently unit-testable.
8. Do not reinvent mature libraries
Before implementing non-trivial generic functionality, check whether a mature library or the standard JDK already solves it well, such as TA4J for technical-analysis primitives or JDK time/concurrency APIs.
Before adding a dependency, state in one line:
What problem it solves that existing code or libraries do not.
Why its maturity, maintenance, and weight are acceptable for a personal trading system.
Mithron-specific strategies, rules, risk logic, and execution safety remain custom because their correctness belongs to this project.
9. Separation of responsibilities
Keep the flow separated:
Market Data → Candle Engine → Indicator/Volume/Structure Engines
           → Market State / Morning Reference → Strategy → Signal
           → Risk / Execution → Order

Do not collapse layers for convenience. Do not invent a new layer or class unless it represents a real responsibility boundary.
Avoid classes named SomethingHelper, SomethingUtil, or SomethingManager created only because code needs somewhere to go. First determine whether the behavior belongs in an existing class.
10. No hidden behavior
Avoid:
Surprising side effects.
Implicit state mutation.
Hidden database writes.
Hidden network calls.
Static mutable state.
Magic values.
Unexplained background behavior.
A method must do what its name and signature imply—nothing more.
11. No silent failure
Never swallow an exception that affects trading correctness.
Never use empty or warning-only handling such as:
try {
    // trading-critical operation
} catch (Exception e) {
}

Any failure that can affect a trading decision must be:
Surfaced.
Logged with enough context to diagnose it.
Reflected in system or data-quality state where relevant.
Handled deterministically.
Fail-closed rather than defaulted around.
12. Time and market-data correctness
Never assume local machine time equals exchange time unless explicitly designed and verified.
Pay explicit attention to:
Time zones.
Exchange timestamps.
Candle-boundary alignment.
Session boundaries.
Previous-trading-day logic through TradingCalendarService or the project’s actual calendar abstraction.
Holidays and expiry days.
Late, duplicate, missing, stale, or out-of-order ticks.
13. Data integrity
Never manufacture, silently interpolate, or silently repair market data.
If data is missing, invalid, stale, or ambiguous, represent that explicitly through the existing quality model:
Candles: LIVE, RECONCILED, REPAIRED, SUSPECT
Context: PARTIAL, COMPLETE, SUSPECT
Do not pretend bad data is good.
daily_data_status = RECONCILED remains the single gate for a backtest or simulation to run for a symbol/day. No code path may bypass this gate for a quick test.
14. Concurrency
Treat shared mutable state as dangerous. Before introducing threads, executors, asynchronous processing, concurrent collections, locks, or scheduled tasks, answer explicitly:
Who owns this state?
Who writes it?
Who reads it?
Can two operations touch it simultaneously?
Can events arrive out of order or be processed twice?
Can a crash leave partial state?
Do not introduce concurrency for theoretical performance gains without a measured need.
15. Persistence must be crash-safe
Never assume clean shutdown. Design for:
Application crash.
Laptop or host power loss.
Network failure.
Database failure.
SmartAPI disconnect.
Partial writes.
Restart and recovery.
Duplicate processing.
Startup and recovery behavior must be deterministic and idempotent.
16. Broker API discipline
Every SmartAPI call site must respect documented provider limits. Do not busy-loop poll because it is convenient.
Retries:
Transient failures such as timeouts and 5xx responses receive bounded retry with backoff.
Exhausted retries become an explicit fail-closed state.
A failed retry must not silently skip required work.
Order placement:
Every order carries a unique client-side order tag/reference.
After an ambiguous response, such as a timeout with unknown outcome, check whether the order already succeeded before retrying.
Duplicate live orders are a worst-case failure mode.
Session/token handling:
Token expiry is handled explicitly.
Refresh behavior is logged without secrets.
A stale session fails closed.
WebSocket reconnect:
A disconnect creates a known data gap.
Subsequent decisions remain SUSPECT until data is backfilled and reconciled.
Reconnect must not resume trading as if no gap occurred.
17. Backtest/live parity
Strategy decision logic must use the exact same code path in backtest and live execution.
Do not maintain parallel implementations that can silently drift. Simulation fills and live order calls may differ, but that boundary must be thin and clearly marked, ideally through one injectable execution boundary already justified by the repository.
18. Unit testing
Do not pursue 100% coverage as a vanity metric. Require 100% coverage of business logic and safety-critical behavior.
Safety-critical areas include:
Strategy decisions.
Custom indicator, volume, and candle calculations.
Market-state classification.
Morning-reference logic.
Every hard filter.
Stale and quality-flag detection.
Risk rules, including position sizing, kill switch, and maximum trades per day.
Order eligibility checks.
Every failure and edge-case path, including empty database, zero-range candle, and missing baseline.
Test both positive and negative cases for every rule:
Valid breakout → trade.
Invalid breakout → no trade.
Sufficient volume → eligible.
Insufficient volume → not eligible.
Fresh data → continue.
Stale data → halt.
Known market state → strategy evaluates.
Unknown market state → no trade.
No logic ships without its tests in the same change. Tests must verify behavior, not merely object existence.
Tests are never weakened merely to make a build pass. If a test fails after a production change:
Determine whether production code is wrong or the test expectation is genuinely obsolete.
State that conclusion explicitly.
Change the test only when its expectation is genuinely obsolete.
Add a regression test for every bug found later.
19. Change hygiene and Git/PR discipline
Every proposed change states one line per changed file explaining why it changed.
One change, one reason.
Do not bundle unrelated refactors.
A candle-rollover fix must not also rename classes, restructure the database, or alter unrelated logging unless explicitly requested.
Commit messages describe the business rule or bug being addressed, not “update files.”
If another issue is noticed, stop and report it separately instead of silently redesigning it.
20. No unnecessary refactoring or speculative optimization
Use this order:
Correct → Simple → Measure → Optimize

Never use:
Complex → Clever → Maybe faster

Do not restructure working code beyond the current task’s scope.
21. Documentation and comments
Comments explain why, not what.
If code needs a comment to explain what it does, first consider rewriting the code to be self-evident.
Comments are reserved for:
Non-obvious business rules.
Safety-critical constraints.
Behavior that looks wrong but is intentional for a documented reason.
Do not add comments that restate obvious code.
22. Facts versus assumptions
If the agent does not know SmartAPI behavior, exchange rules, library behavior, or database semantics, it must say so and verify through reliable documentation or project evidence.
Never present a plausible assumption as a fact.
23. Production safety boundary
SIMULATION and LIVE are strictly separated code paths.
Before modifying code that can reach real order placement:
Trace the complete execution path.
Understand all guards and mode checks.
Confirm the order boundary.
Confirm idempotency behavior.
Confirm secrets are not exposed.
No API key, TOTP secret, JWT, database password, or other secret may be hardcoded or committed.
24. Logging
Trading-critical logs must answer:
What happened?
When did it happen?
Why did it happen?
Which instrument or scope was affected?
Which timestamp and lifecycle state applied?
What decision was made?
What was the reason?
Never log credentials, tokens, cookies, passwords, complete authorization headers, or sensitive payloads.
Do not spam logs with routine messages that provide no diagnostic value.
25. Before writing code: understand the system first
For every non-trivial task, do not start coding immediately. First:
Inspect the relevant actual modules and trace the existing flow.
Confirm the relevant files are available; otherwise request them.
Identify dependencies and existing abstractions.
Decide where the change actually belongs.
Identify all existing tests affected.
Identify the smallest safe change.
Identify the failure-closed behavior.
State any unresolved evidence gap.
The agent must not generate full replacement classes from a partial or imagined repository.
26. Verification after every change
Use this sequence:
Compile → Unit tests → Relevant integration tests → Inspect the diff
→ Check for unintended changes → Report what was actually verified

The agent must never claim that everything works without actually running the relevant tests.
If a database, provider, runtime, source tree, or test suite was unavailable, state that explicitly.
27. Keep Mithron understandable
Mithron should remain small enough that one engineer can hold the complete system in their head.
When choosing between:
Small, deterministic, testable, understandable
Extensible, framework-heavy, abstract
Choose the first unless the second is required by a demonstrated current need.
Pre-flight checklist
Before starting any task:
Do I understand which existing engine, service, table, and test this touches?
Have I inspected the actual files rather than imagined them?
If the files are unavailable, have I asked for the exact files before deciding?
Does the functionality already exist somewhere in the repository?
Is there a mature library that already solves this?
What is the smallest change that fully satisfies the requirement?
Which existing tests will this affect?
What is the fail-closed behavior for each failure path?
Does the change touch LIVE order execution or secrets?
Post-flight checklist
Before calling a change done:
Does it include tests for positive, negative, edge, and failure cases where applicable?
Did I avoid adding classes, interfaces, abstractions, or configuration not strictly needed today?
Did I touch only files required for this change?
Does every trading-critical failure fail closed?
Is SIMULATION/LIVE separation intact?
If order placement is involved, is retry idempotent?
Did I list every changed file and its reason?
Did I compile the project?
Did I run unit tests?
Did I run relevant integration tests?
Did I inspect the diff for unintended changes?
Did I clearly state what was not verified?
File layout recommendation


Before starting Phase 4, perform a read-only audit of Phase 1–3 against these rules rather than assuming past code already complies.


