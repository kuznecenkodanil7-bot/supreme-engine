package ru.mond.minibaritone;

public final class BotSettings {
    public boolean allowBreak = true;
    public boolean allowPlace = true;
    public boolean allowSprint = true;

    public int maxSearchNodes = 12_000;
    public int maxFallHeight = 3;

    public double breakCost = 12.0;
    public double placeCost = 8.0;
    public double jumpCost = 2.5;
    public double fallCost = 2.0;
}
