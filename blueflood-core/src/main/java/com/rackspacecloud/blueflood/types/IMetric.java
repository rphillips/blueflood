package com.rackspacecloud.blueflood.types;

public interface IMetric {
    public Object getValue();
    public Locator getLocator();
    public long getCollectionTime();
    public int getTtlInSeconds();
    public void setTtlInSeconds(int seconds);
    public RollupType getRollupType();
}
