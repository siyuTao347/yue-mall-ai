PYTHON ?= python3

.PHONY: validate test dry-run smoke evaluate contracts datasets
validate:
	$(PYTHON) scripts/build_datasets.py --check
	$(PYTHON) scripts/run_evaluation.py --validate

test:
	$(PYTHON) -m unittest discover -s tests -v

dry-run:
	$(PYTHON) scripts/run_evaluation.py --suite all --dry-run

smoke:
	$(PYTHON) scripts/run_evaluation.py --env-file .env --fixtures fixtures/local.json --suite smoke

evaluate:
	$(PYTHON) scripts/run_evaluation.py --env-file .env --fixtures fixtures/local.json --suite all --mode live --judge required --trials 3

contracts:
	$(PYTHON) scripts/run_contract_tests.py

datasets:
	$(PYTHON) scripts/build_datasets.py
