package metrics

import "sync"

type Counter struct {
	mu     sync.RWMutex
	values map[string]uint64
}

func New() *Counter             { return &Counter{values: map[string]uint64{}} }
func (c *Counter) Inc(k string) { c.mu.Lock(); c.values[k]++; c.mu.Unlock() }
func (c *Counter) Snapshot() map[string]uint64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	m := make(map[string]uint64, len(c.values))
	for k, v := range c.values {
		m[k] = v
	}
	return m
}
