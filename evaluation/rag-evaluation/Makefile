.PHONY: dataset evaluate ci

dataset:
	python3 scripts/build_golden_dataset.py

evaluate: dataset
	python3 scripts/run_evaluation.py --fail-on-quality

ci:
	python3 scripts/build_golden_dataset.py
	python3 scripts/run_evaluation.py --visibility PUBLIC --fail-on-quality
