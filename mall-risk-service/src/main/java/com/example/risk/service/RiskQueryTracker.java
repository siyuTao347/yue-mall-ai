package com.example.risk.service;

public final class RiskQueryTracker {
    private int count;

    public void increment() {
        count++;
    }

    public void increment(int queries) {
        count += queries;
    }

    public int count() {
        return count;
    }
}
