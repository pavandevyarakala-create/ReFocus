# Streak Milestone & Discipline Levels Documentation

This document describes the current implementation of the streak milestone and discipline level systems in the ReelTrak application exactly as they exist in the codebase.

## 1. Streak Milestones & Triggers
Streak milestones correspond directly to the thresholds required to advance in **Discipline Levels**. The configured milestones are:
- **7 days** (Focused Milestone)
- **30 days** (Consistent Milestone)
- **50 days** (Disciplined Milestone)
- **100 days** (Master Milestone)
- **365 days** (Ultimate Milestone/Cap)

### Milestone Details
- **Trigger Condition**: Having the `longestStreak` property (computed chronologically across all tracked days) reach or exceed the milestone threshold.
- **Trigger Mode**: Evaluated dynamically based on history.
- **UI Shown**:
  - Main Dashboard (in the mini Streak progress card) and Calendar pages display a custom pulsating `LocalFireDepartment` vector icon inside a highlight circle (`Color(0xFFFF5722)`).
  - A linear progress indicator (`LinearProgressIndicator`) displays the progress to the next level based on the current milestone bounds.
- **Execution**: Evaluated dynamically upon mapping daily blocks; calculated on-the-fly from history records.

---

## 2. Discipline Levels
Discipline levels are assigned dynamically based on the user's `longestStreak` reached:

| Level Name | Longest Streak Required | Progress Calculation Formula to Next Level |
| :--- | :--- | :--- |
| **Seedling** | `0` to `6` days | `longestStreak / 7f` |
| **Focused** | `7` to `29` days | `(longestStreak - 7) / 23f` |
| **Consistent** | `30` to `49` days | `(longestStreak - 30) / 20f` |
| **Disciplined** | `50` to `99` days | `(longestStreak - 50) / 50f` |
| **Master** | `100` to `364` days | `(longestStreak - 100) / 265f` |
| **Legend** | `365+` days | `1.0f` (Fully completed progress) |

### Behaviors & Constraints
- **Can Levels Decrease?**: No. Because levels are mapped to `longestStreak` (and not `currentStreak`), they represent lifetime-best achievements and do not decrease once a higher level is unlocked.
- **How Skull Days Affect Level**: A Skull Day resets the `currentStreak` to `0`, but it does not diminish the `longestStreak`. Thus, a Skull Day does not cause the user to lose their current Discipline Level.

---

## 3. Mathematical Formulas
- **Active Day Condition**: A day is counted as an active day if the user scrolled at least 1 reel (`totalReelsScrolled > 0`) or set a daily goal (`dailyGoal > 0`).
- **Success Rate**: 
  $$\text{Success Rate} = \frac{\text{Successful Days}}{\text{Active Days}}$$
  *Note: A day is successful if it was active and was not a Skull Day (i.e. did not exceed the set daily limit).*
- **Current Streak Reset**:
  $$\text{currentStreak} = 0 \quad \text{if} \quad \text{day.isSkullDay is true}$$
