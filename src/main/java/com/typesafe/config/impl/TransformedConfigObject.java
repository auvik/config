package com.typesafe.config.impl;

import java.util.Set;

class TransformedConfigObject extends AbstractConfigObject {

    private AbstractConfigObject underlying;

    TransformedConfigObject(ConfigTransformer transformer,
            AbstractConfigObject underlying) {
        super(underlying.origin(), transformer);
        this.underlying = underlying;
    }

    @Override
    public boolean containsKey(String key) {
        return underlying.containsKey(key);
    }

    @Override
    public Set<String> keySet() {
        return underlying.keySet();
    }

    @Override
    public Object unwrapped() {
        return underlying.unwrapped();
    }

    @Override
    protected AbstractConfigValue peek(String key) {
        return underlying.peek(key);
    }
}