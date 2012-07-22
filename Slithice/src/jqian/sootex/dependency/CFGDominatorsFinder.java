/* Soot - a J*va Optimization Framework
 * Copyright (C) 2005 Navindra Umanee <navindra@cs.mcgill.ca>
 * Copyright (C) 2007 Eric Bodden
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package jqian.sootex.dependency;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import soot.toolkits.graph.DirectedGraph;
import soot.toolkits.graph.DominatorsFinder;


/**
  * Calculate dominators for basic blocks.
 * <p> Uses the algorithm contained in Dragon book, pg. 670-1.
 * <pre>
 *       D(n0) := { n0 }
 *       for n in N - { n0 } do D(n) := N;
 *       while changes to any D(n) occur do
 *         for n in N - {n0} do
 *             D(n) := {n} U (intersect of D(p) over all predecessors p of n)
 * </pre>
 * 2007/07/03 - updated to use {@link BitSet}s instead of {@link HashSet}s, as the most expensive
 * operation in this algorithm used to be cloning of the fullSet, which is very cheap
 * for {@link BitSet}s.
 *
 * @author Navindra Umanee
 * @author Eric Bodden
 * 
 * TODO: 这里性能似乎还有些问题,迭代式为考虑优化的迭代顺序,求最近必经节点用链表操作,有点慢
 * 原先算法在死循环处理上有比较大的问题
 **/
public class CFGDominatorsFinder<N> implements DominatorsFinder<N>
{
    protected DirectedGraph<N> graph;
    protected BitSet fullSet;
    protected List<N> heads;
    protected Map<N,BitSet> nodeToFlowSet;
    protected Map<N,Integer> nodeToIndex;
    protected Map<Integer,N> indexToNode;
    protected int lastIndex = 0;
    protected boolean containInfiniteLoops;

    public CFGDominatorsFinder(DirectedGraph<N> graph)
    {
        this.graph = graph;
        doAnalysis();
    }

    protected void doAnalysis()
    {
        heads = graph.getHeads();
        nodeToFlowSet = new HashMap<N, BitSet>();
        nodeToIndex = new HashMap<N, Integer>();
        indexToNode = new HashMap<Integer,N>();
    
        //build full set
        fullSet = new BitSet(graph.size());
        fullSet.flip(0, graph.size());//set all to true
        
        //set up domain for intersection: head nodes are only dominated by themselves,
        //other nodes are dominated by everything else
        for(Iterator<N> i = graph.iterator(); i.hasNext();){
            N o = i.next();
            if(heads.contains(o)){
                BitSet self = new BitSet();
                self.set(indexOf(o));
                nodeToFlowSet.put(o, self);
            }
            else{
                nodeToFlowSet.put(o, fullSet);
            }
        }
    
        HashSet<N> modifiedNodes = new HashSet<N>();
        boolean changed = true;
        do{
            changed = false;
            for(Iterator<N> i = graph.iterator(); i.hasNext();){
                N o = i.next();
                if(heads.contains(o)) continue;
    
                //initialize to the "neutral element" for the intersection
                //this clone() is fast on BitSets (opposed to on HashSets)
                // XXX: modified here, avoid unnecessary clone() and and() operation
                List<N> preds = graph.getPredsOf(o);
                assert !preds.isEmpty();
                
                Iterator<N> j = preds.iterator();
                N first = j.next();
                BitSet predSet = nodeToFlowSet.get(first);
                BitSet predsIntersect = (BitSet) predSet.clone();
    
                //intersect over all predecessors
                for(; j.hasNext();){
                    predSet = nodeToFlowSet.get(j.next());
                    predsIntersect.and(predSet);
                }
    
                BitSet oldSet = nodeToFlowSet.get(o);
                //each node dominates itself
                predsIntersect.set(indexOf(o));
                if(!predsIntersect.equals(oldSet)){
                    nodeToFlowSet.put(o, predsIntersect);
                    changed = true;
                    
                    modifiedNodes.add(o);
                }
            }
        } while(changed);
        
        ///////////////////////////////////////////////////////////////////////////////
        //XXX check whether there are infinite loops
        modifiedNodes.addAll(heads);
        containInfiniteLoops = false;
        List<N> tails = graph.getTails();
        for(Iterator<N> it = graph.iterator(); it.hasNext();){
        	N node = it.next();
        	if(!tails.contains(node) && !modifiedNodes.contains(node)){ 
        		containInfiniteLoops = true;
        		break;
        	}        	
        }
    }
    
    public boolean containInfiniteLoops(){
    	return containInfiniteLoops;
    }
    
    protected int indexOf(N o) {
        Integer index = nodeToIndex.get(o);
        if(index==null) {
            index = lastIndex;
            nodeToIndex.put(o,index);
            indexToNode.put(index,o);
            lastIndex++;
        }
        return index;
    }
    
    public DirectedGraph<N> getGraph()
    {
        return graph;
    }
    
    public List<N> getDominators(Object node)
    {
        //reconstruct list of dominators from bitset
        List<N> result = new ArrayList<N>();
        BitSet bitSet = nodeToFlowSet.get(node);
        for(int i=0;i<bitSet.length();i++) {
            if(bitSet.get(i)) {
                result.add(indexToNode.get(i));
            }
        }
        return result;
    }

    public N getImmediateDominator(Object node)
    {
        // root node
        if(getGraph().getHeads().contains(node))
            return null;

	// could be memoised, I guess
        // XXX: improve efficiency
        @SuppressWarnings("unchecked")
		List<N> preds = graph.getPredsOf((N)node);
        if(preds.size()==1){
        	return preds.iterator().next();
        }

        List<N> dominatorsList = getDominators(node);
        dominatorsList.remove(node);

        Iterator<N> dominatorsIt = dominatorsList.iterator();
        N immediateDominator = null;

        while((immediateDominator == null) && dominatorsIt.hasNext()){
            N dominator = dominatorsIt.next();

            if(isDominatedByAll(dominator, dominatorsList))
                immediateDominator = dominator;
        }

        assert immediateDominator!=null;
        
        return immediateDominator;
    }

    public boolean isDominatedBy(N node, N dominator)
    {
        return getDominators(node).contains(dominator);
    }

    public boolean isDominatedByAll(N node, Collection<N> dominators)
    {
        return getDominators(node).containsAll(dominators);
    }
}
