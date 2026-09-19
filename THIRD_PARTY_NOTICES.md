# Third-party notices

MiniWindowGuard 4.1 uses an original implementation of its floating-window engine.

The following open-source projects were studied as public architectural references:

- YAMF² / YAMFsquared — https://github.com/kaii-lb/YAMFsquared
- YAMF — https://github.com/duzhaokun123/YAMF
- FreeformShell — https://github.com/bravoyush/FreeformShell

No floating-window implementation source code from those projects is incorporated into the MiniWindowGuard 4.1 window engine. The current implementation was rewritten for this project around Android system concepts including VirtualDisplay, TextureView, ActivityTaskManager and InputManager.

MiniWindowGuard retains its own system-server foreground/lifecycle protection and diagnostic export system.

This repository remains distributed under GPLv3. See LICENSE.
