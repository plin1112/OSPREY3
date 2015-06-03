/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.confspace;

import java.io.Serializable;
import java.util.ArrayList;

/**
 *
 * @author mhall44
 */
public class TupleMatrix<T> implements Serializable {
    //We will need "matrices" of quantities defined
    //for example, the energy matrix (T=Double) stores single, pairwise, and higher-order energies
    //and we'll also have pruning (T=Boolean) and EPIC (T=EPoly) matrices
    //we'll store things as ArrayLists to make it easier to merge residues, partition RCs, etc.
    //and also to facilitate generics
    
    //note: tuples are sets not ordered pairs, i.e. E(i_r,j_s) = E(j_s,i_r), and pruning (i_r,j_s) means pruning (j_s,i_r)
    
    public ArrayList<ArrayList<ArrayList<ArrayList<T>>>> pairwise;//pairwise energies.  Set up 4D to save space
    //indices: res1, res2, RC1, RC2 where res1>res2
    public ArrayList<ArrayList<T>> oneBody;//intra+shell
    
    //public ArrayList<ArrayList<ArrayList<ArrayList<HigherTupleFinder<T>>>>> higherTerms;//look up higher terms by pair
    
    //maybe separate intra too?
    
    
    //The above all use RCs that are indexed by residues
    //the following lists indicate exactly what RCs those are
    //private because changing it may require re-indexing everything else
    //METHODS TO ADD RCs?
    
    //private ArrayList<ArrayList<RC>> RCList;
    
    /*
    //reverse lookup: for each residue, a lookup first by AA type, then by 
    //Return the residue-based RC number
    ArrayList<TreeMap<String,TreeMap<Integer,
    */
    
    
    double pruningInterval;//This matrix needs to hold entries for all RCs
    //that cannot be pruned with the specified pruning interval (Ew + Ival)
    //i.e. the matrix must describe all conformations within pruningInterval 
    //of the lowest pairwise lower bound
    
    public TupleMatrix(ConfSpace cSpace, double pruningInterval){
        //allocate the matrix based on the provided conformational space
        init(cSpace.numPos, cSpace.getNumRCsAtPos(), pruningInterval);
    }
    
    
    public TupleMatrix(int numPos, int[] numAllowedAtPos, double pruningInterval){
        //allocate the matrix based on the provided conformational space size
        //also specify what pruningInterval it's valid up to
        init(numPos,numAllowedAtPos,pruningInterval);
    }
    
    
    private void init(int numPos, int[] numAllowedAtPos, double pruningInterval) {
        
        this.pruningInterval = pruningInterval;
        
        oneBody = new ArrayList<>();
        pairwise = new ArrayList<>();
        
        for(int pos=0; pos<numPos; pos++){
                        
            int numRCs = numAllowedAtPos[pos];
            
            //preallocate oneBody for this position
            ArrayList<T> oneBodyAtPos = new ArrayList<>();
            for(int rc=0; rc<numRCs; rc++)//preallocate oneBody
                oneBodyAtPos.add(null);
            
            oneBodyAtPos.trimToSize();//we may need to save space so we'll trim everything to size
            oneBody.add(oneBodyAtPos);

            
            ArrayList<ArrayList<ArrayList<T>>> pairwiseAtPos = new ArrayList<>();
            //may want to leave some pairs of positions null if negligible interaction expected...
            //handle later though
            for(int pos2=0; pos2<pos; pos2++){
                
                int numRCs2 = numAllowedAtPos[pos2];

                ArrayList<ArrayList<T>> pairwiseAtPair = new ArrayList<>();
                
                for(int rc=0; rc<numRCs; rc++){
                    ArrayList<T> pairwiseAtRC = new ArrayList<>();
                    for(int rc2=0; rc2<numRCs2; rc2++)
                        pairwiseAtRC.add(null);
                    
                    pairwiseAtRC.trimToSize();
                    pairwiseAtPair.add(pairwiseAtRC);
                }
                
                pairwiseAtPair.trimToSize();
                pairwiseAtPos.add(pairwiseAtPair);
            }
            
            pairwiseAtPos.trimToSize();
            pairwise.add(pairwiseAtPos);
        }
        
        oneBody.trimToSize();
        pairwise.trimToSize();
    }
    
    
    public T getPairwise(int res1, int index1, int res2, int index2){
        //working with residue-specific RC indices directly.  
        if(res1>res2)
            return pairwise.get(res1).get(res2).get(index1).get(index2);
        else
            return pairwise.get(res2).get(res1).get(index2).get(index1);
    }
    
    public T getOneBody(int res, int index){
        return oneBody.get(res).get(index);
    }
    
    
    public void setPairwise(int res1, int index1, int res2, int index2, T val){

        if(res1>res2)
            pairwise.get(res1).get(res2).get(index1).set(index2,val);
        else
            pairwise.get(res2).get(res1).get(index2).set(index1,val);
    }
    
    
    public void setOneBody(int res, int index, T val){
        oneBody.get(res).set(index,val);
    }
    
    
    public int numRCsAtPos(int pos){
        return oneBody.get(pos).size();
    }
    
    
    public void setTupleValue(RCTuple tup, T val){
        //assign the given value to the specified RC tuple
        int tupSize = tup.pos.size();
        
        if(tupSize==1)//just a one-body quantity
            setOneBody( tup.pos.get(0), tup.RCs.get(0), val);
        if(tupSize==2)//two-body
            setPairwise( tup.pos.get(0), tup.RCs.get(0), tup.pos.get(1), tup.RCs.get(1), val );
        else
            throw new UnsupportedOperationException( "ERROR: Not supporting tuple size " + tupSize );
    }

    public double getPruningInterval() {
        return pruningInterval;
    }
 
    
    
    
}
