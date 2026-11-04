package dev.jaderss.lvr.webflux.model;

public record Account(long id, String owner, String currency, long balanceMinor) {}
