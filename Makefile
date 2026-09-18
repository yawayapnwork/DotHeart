# Installs the push_art CLI (scripts/push_art) onto PATH.
#
#   make install                 # installs to /usr/local/bin (likely needs sudo)
#   sudo make install
#   make install PREFIX=$HOME/.local   # user-local install, no sudo needed
#                                       # (requires $HOME/.local/bin on PATH)
#   make uninstall
#
# Uses only the standard `install` and `python3` UNIX tools - no package
# manager, no third-party build system.

PREFIX  ?= /usr/local
BIN_DIR := $(PREFIX)/bin
SCRIPT  := push_art
SCRIPT_SRC := scripts/$(SCRIPT)

.PHONY: install uninstall check

check:
	@python3 -c "import ast; ast.parse(open('$(SCRIPT_SRC)', encoding='utf-8').read())"
	@echo "OK: $(SCRIPT_SRC) is syntactically valid Python."

install: check
	install -d "$(BIN_DIR)"
	install -m 0755 "$(SCRIPT_SRC)" "$(BIN_DIR)/$(SCRIPT)"
	@echo "Installed $(SCRIPT) to $(BIN_DIR)/$(SCRIPT)"
	@echo "Run '$(SCRIPT) --help' from any directory to confirm."

uninstall:
	rm -f "$(BIN_DIR)/$(SCRIPT)"
	@echo "Removed $(BIN_DIR)/$(SCRIPT)"
