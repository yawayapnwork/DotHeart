# Installs the push_art and push_event CLIs (scripts/) onto PATH.
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
SCRIPTS := push_art push_event

.PHONY: install uninstall check

check:
	@for s in $(SCRIPTS); do \
		python3 -c "import ast,sys; ast.parse(open('scripts/$$s', encoding='utf-8').read())" || exit 1; \
		echo "OK: scripts/$$s is syntactically valid Python."; \
	done

install: check
	install -d "$(BIN_DIR)"
	@for s in $(SCRIPTS); do \
		install -m 0755 "scripts/$$s" "$(BIN_DIR)/$$s" || exit 1; \
		echo "Installed $$s to $(BIN_DIR)/$$s"; \
	done
	@echo "Run 'push_art --help' or 'push_event --help' from any directory to confirm."

uninstall:
	@for s in $(SCRIPTS); do \
		rm -f "$(BIN_DIR)/$$s"; \
		echo "Removed $(BIN_DIR)/$$s"; \
	done
