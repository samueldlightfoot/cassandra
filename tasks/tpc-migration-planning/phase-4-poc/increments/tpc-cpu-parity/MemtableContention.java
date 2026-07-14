import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.Set;
import java.util.TreeMap;

/**
 * Dumps TrieMemtable per-shard contention counters + write-path thread-pool queueing from a running
 * Cassandra node over JMX. GC-immune domain counters for the TPC shared-vs-per-shard comparison.
 *
 *   javac MemtableContention.java && java MemtableContention [host] [port]   (default 127.0.0.1 7199)
 */
public class MemtableContention
{
    public static void main(String[] args) throws Exception
    {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        String port = args.length > 1 ? args[1] : "7199";
        JMXServiceURL url = new JMXServiceURL(
            "service:jmx:rmi:///jndi/rmi://" + host + ":" + port + "/jmxrmi");
        try (JMXConnector jmxc = JMXConnectorFactory.connect(url, null))
        {
            MBeanServerConnection mbs = jmxc.getMBeanServerConnection();

            System.out.println("=== TrieMemtable contention (keyvalue) ===");
            long contended = 0, uncontended = 0;
            for (ObjectName on : sorted(mbs, "org.apache.cassandra.metrics:type=TrieMemtable,keyspace=cassandra_easy_stress,scope=keyvalue,*"))
            {
                String name = on.getKeyProperty("name");
                Long count = readLong(mbs, on, "Count");
                Double mean = readDouble(mbs, on, "Mean");
                StringBuilder sb = new StringBuilder("  " + pad(name, 30));
                if (count != null) sb.append(" count=" + count);
                if (mean != null) sb.append(String.format("  mean=%.1fns", mean));
                System.out.println(sb);
                if (name != null && count != null)
                {
                    if (name.startsWith("Contended")) contended += count;
                    else if (name.startsWith("Uncontended")) uncontended += count;
                }
            }
            long total = contended + uncontended;
            if (total > 0)
                System.out.printf("  DERIVED total_puts=%d  contended=%d  uncontended=%d  contended/1k=%.2f  contended%%=%.3f%n",
                                  total, contended, uncontended, 1000.0 * contended / total, 100.0 * contended / total);

            System.out.println("=== write-path thread pools (queueing) ===");
            for (ObjectName on : sorted(mbs, "org.apache.cassandra.metrics:type=ThreadPools,*"))
            {
                String path = on.getKeyProperty("path");
                String pool = on.getKeyProperty("scope");
                String name = on.getKeyProperty("name");
                if (pool == null) continue;
                boolean writePath = pool.startsWith("Shard-") || pool.contains("Native-Transport")
                                    || pool.equals("MutationStage");
                if (!writePath) continue;
                if (!("TotalBlockedTasks".equals(name) || "CurrentlyBlockedTasks".equals(name)
                      || "PendingTasks".equals(name) || "TotalWaitTasks".equals(name)))
                    continue;
                Long v = readLong(mbs, on, "Count");
                if (v == null) v = readLong(mbs, on, "Value");
                if (v != null && v != 0)
                    System.out.printf("  %-22s %-22s %s%n", pool, name, v);
            }
        }
    }

    static java.util.Collection<ObjectName> sorted(MBeanServerConnection mbs, String pattern) throws Exception
    {
        TreeMap<String, ObjectName> m = new TreeMap<>();
        Set<ObjectName> names = mbs.queryNames(new ObjectName(pattern), null);
        for (ObjectName on : names) m.put(on.toString(), on);
        return m.values();
    }

    static Long readLong(MBeanServerConnection mbs, ObjectName on, String attr)
    {
        try { Object o = mbs.getAttribute(on, attr); return o == null ? null : ((Number) o).longValue(); }
        catch (Exception e) { return null; }
    }

    static Double readDouble(MBeanServerConnection mbs, ObjectName on, String attr)
    {
        try { Object o = mbs.getAttribute(on, attr); return o == null ? null : ((Number) o).doubleValue(); }
        catch (Exception e) { return null; }
    }

    static String pad(String s, int n)
    {
        if (s == null) s = "?";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }
}
