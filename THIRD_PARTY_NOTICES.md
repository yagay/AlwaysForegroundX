# Third-party notices

MiniWindowGuard 4.x reworks its floating-window subsystem around the same core architecture used by the open-source YAMF family:

- YAMF² / YAMFsquared — https://github.com/kaii-lb/YAMFsquared
- YAMF — https://github.com/duzhaokun123/YAMF

Those projects are distributed under the GNU General Public License version 3 (GPLv3).

MiniWindowGuard does not keep its former display-0 Freeform/WCT mini-window backend. The 4.x design uses a system-server VirtualDisplay container: a target Task is moved to a dedicated VirtualDisplay, rendered into a TextureView/Surface hosted by a system overlay, and input events are forwarded to that display.

MiniWindowGuard retains its own system-server foreground/lifecycle protection and diagnostic export system.

This repository is distributed under GPLv3. See LICENSE.
