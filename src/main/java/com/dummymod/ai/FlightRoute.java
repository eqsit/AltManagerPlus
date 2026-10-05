package com.dummymod.ai;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Bounded six-axis routing with a frontier retained between client ticks. */
public final class FlightRoute {
    public record Point(int x,int y,int z) {
        int distance(Point p){return Math.abs(x-p.x)+Math.abs(y-p.y)+Math.abs(z-p.z);}
    }
    private record Node(Point p,int cost,int score){}
    public static final class Search {
        private final Point start;
        private final Set<Point> goals;
        private final ToIntFunction<Point> enter;
        private final int limit,radius,maxCost;
        private final PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingInt(Node::score).thenComparing(Comparator.comparingInt(Node::cost).reversed()));
        private final Map<Point,Integer> cost=new HashMap<>(),passability=new HashMap<>();
        private final Map<Point,Point> previous=new HashMap<>();
        private int visited;
        private boolean done;
        private List<Point> result=List.of();
        public Search(Point start,Collection<Point> goals,ToIntFunction<Point> enter,int limit) {
            this(start,goals,enter,limit,Integer.MAX_VALUE);
        }
        public Search(Point start,Collection<Point> goals,ToIntFunction<Point> enter,int limit,int maxCost) {
            this.start=start;this.goals=Set.copyOf(goals);this.enter=enter;this.limit=limit;
            this.maxCost=maxCost;
            radius=goals.stream().mapToInt(start::distance).max().orElse(0)+32;
            done=goals.isEmpty();cost.put(start,0);open.add(new Node(start,0,heuristic(start)));
        }
        private int heuristic(Point p){return goals.stream().mapToInt(p::distance).min().orElse(0);}
        public boolean done(){return done;}
        public int visited(){return visited;}
        public List<Point> result(){return result;}
        public void advance(int nodes,long nanos) {
            long until=System.nanoTime()+nanos;
            int[][] directions={{0,1,0},{1,0,0},{0,0,1},{-1,0,0},{0,0,-1},{0,-1,0}};
            while(!done && !open.isEmpty() && visited<limit && nodes-->0 && System.nanoTime()<until) {
                Node n=open.remove();if(n.cost!=cost.getOrDefault(n.p,Integer.MAX_VALUE))continue;
                visited++;
                if(goals.contains(n.p)) {
                    LinkedList<Point> path=new LinkedList<>();Point p=n.p;
                    while(!p.equals(start)){path.addFirst(p);p=previous.get(p);}
                    result=path.isEmpty()?List.of(start):List.copyOf(path);done=true;return;
                }
                for(int[] d:directions) {
                    Point p=new Point(n.p.x+d[0],n.p.y+d[1],n.p.z+d[2]);
                    if(p.distance(start)>radius || cost.getOrDefault(p,Integer.MAX_VALUE)<=n.cost+1)continue;
                    int step=passability.computeIfAbsent(p,enter::applyAsInt);
                    if(step<=0 || n.cost+step>maxCost || cost.getOrDefault(p,Integer.MAX_VALUE)<=n.cost+step)continue;
                    cost.put(p,n.cost+step);previous.put(p,n.p);open.add(new Node(p,n.cost+step,n.cost+step+heuristic(p)));
                }
            }
            if(open.isEmpty() || visited>=limit)done=true;
        }
    }
    public static List<Point> find(Point start,Point goal,Predicate<Point> free,int limit) {
        if(!free.test(goal))return List.of();
        Search search=new Search(start,List.of(goal),p->free.test(p)?1:0,limit);
        search.advance(limit,Long.MAX_VALUE/4);return search.result();
    }
}
