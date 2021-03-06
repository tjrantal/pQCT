package sc.fiji.pQCT.utils;

/*
	Cluster points into two groups. 
	Calculate distance to all other points
	Start from the points that are the farthest apart
	Add next point to the cluster with the minimum distance
*/

import sc.fiji.pQCT.selectroi.Coordinate;
import java.util.ArrayList;
import java.util.Collections;

//Debugging
//import ij.IJ;

public class ClusterPoints{
	public ArrayList<Coordinate> cluster1=null;
	public ArrayList<Coordinate> cluster2=null;


	//Testing
	public static void main(String[] a){

		ArrayList<Coordinate> testCoordinates = new ArrayList<Coordinate>();
		//First cluster
		testCoordinates.add(new Coordinate(0,0));
		testCoordinates.add(new Coordinate(1,0));
		testCoordinates.add(new Coordinate(2,0));
		testCoordinates.add(new Coordinate(3,0));
		//Second cluster
		testCoordinates.add(new Coordinate(1,2));
		testCoordinates.add(new Coordinate(2,2));
		testCoordinates.add(new Coordinate(3,2));
		ClusterPoints cp = new ClusterPoints(testCoordinates);
		
		System.out.println("Result");
		for (int i = 0; i<cp.cluster1.size();++i){
			System.out.println(String.format("Cluster 1 i %05d x %04d y %04d",i,(int) cp.cluster1.get(i).ii,(int) cp.cluster1.get(i).jj));
		}
		for (int i = 0; i<cp.cluster2.size();++i){
			System.out.println(String.format("Cluster 2 i %05d x %04d y %04d",i,(int) cp.cluster2.get(i).ii,(int) cp.cluster2.get(i).jj));
		}
		
	}
	
	public ClusterPoints(ArrayList<Coordinate> coordinates){
		ArrayList<ArrayList<Distance>> distances = null;

		
		//Calculate distances between coordinates
		//IJ.log("Start calculating distances");
		distances = new ArrayList<ArrayList<Distance>>(coordinates.size());
		//IJ.log(String.format("Start calculating distances %d",distances.size()));
		double maxDist = Double.NEGATIVE_INFINITY;
		int[] maxIndex = new int[]{0,0};
		for (int i =0; i<coordinates.size();++i){
			distances.add(new ArrayList<Distance>(coordinates.size()));
			for (int j =0; j<coordinates.size();++j){
				if (i != j){
					distances.get(i).add(new Distance(coordinates.get(i),coordinates.get(j),i,j));
					if (distances.get(i).get(distances.get(i).size()-1).distance > maxDist){
						maxIndex[0] = i;
						maxIndex[1] = j;
						maxDist = distances.get(i).get(distances.get(i).size()-1).distance;
					}
					
				}
			}
			//Sort distances here
			Collections.sort(distances.get(i));
			//IJ.log(String.format("Coordinate calculation i %05d",i));
		}
		 	//Sort distances here
		//IJ.log(String.format("Sorted distances %d",distances.size()));

		//System.out.println("All distances");
		//printDistanceArray(distances);
		
		//Start going through the dataset with the farthest apart points as the cluster centres
		ArrayList<Coordinate> classified = new ArrayList<Coordinate>(coordinates.size());	//Retain information on whether a coordinate is classified or not
		cluster1 = new ArrayList<Coordinate>(coordinates.size());
		cluster2 = new ArrayList<Coordinate>(coordinates.size());
		cluster1.add(new Coordinate(coordinates.get(maxIndex[0])));
		cluster2.add(new Coordinate(coordinates.get(maxIndex[1])));
		classified.add(new Coordinate(coordinates.get(maxIndex[0])));	//Add the classified coordinate index
		classified.add(new Coordinate(coordinates.get(maxIndex[1])));	//Add the classified coordinate index
		//Loop through the coordinates to classify each, stop once all coordinates have been classified
		ArrayList<Distance> temp1 = getDistances(distances,cluster1.get(cluster1.size()-1),classified); 
		ArrayList<Distance> temp2 = getDistances(distances,cluster2.get(cluster2.size()-1),classified);
		
		while (classified.size() <coordinates.size()){
			//System.out.println(String.format("c1 looking for %.0f %.0f",cluster1.get(cluster1.size()-1).ii,cluster1.get(cluster1.size()-1).jj));
			
			//System.out.println(String.format("printDist temp2"));
			//printDistanceArray(temp2);
			//Add the coordinate with the shortest distance to the pertinent cluster
			if (temp1.get(0).distance <= temp2.get(0).distance){
				//Handle closest to temp1
				ArrayList<Distance> temp3 = getDistances2ndIndex(temp2, temp1.get(0).j);
				if (temp3.size() < 1 || temp1.get(0).distance <= temp3.get(0).distance){
					cluster1.add(new Coordinate(temp1.get(0).b));
				}else{
					cluster2.add(new Coordinate(temp1.get(0).b));
				}
				classified.add(new Coordinate(temp1.get(0).b));				
				temp1 = getDistances(distances,cluster1.get(cluster1.size()-1),classified);		//Recalculate distances
			}else{
				//Handle closest to temp2
				ArrayList<Distance> temp3 = getDistances2ndIndex(temp1, temp2.get(0).j);
				if (temp3.size() < 1 || temp2.get(0).distance <= temp3.get(0).distance){
					cluster2.add(new Coordinate(temp2.get(0).b));
				}else{
					cluster1.add(new Coordinate(temp2.get(0).b));
				}
				classified.add(new Coordinate(temp2.get(0).b));
				temp2 = getDistances(distances,cluster2.get(cluster2.size()-1),classified);	//Recalculate distances
				//System.out.println(String.format("printDist classified size %05d",classified.size()));
			}
			//IJ.log(String.format("printDist classified size %05d",classified.size()));
		}
		
		
	}
	
	private void printDistanceArray(ArrayList<Distance> a){
		for (int i = 0; i<a.size();++i){
			System.out.println(String.format("printDist index %05d i %04d j %04d distance %.2f",i,(int) a.get(i).i,(int) a.get(i).j,a.get(i).distance));
		}
		
	}
	
	//Get distances for the coordinate of interest. Exclude distances to already classified pixels
	private ArrayList<Distance> getDistances(ArrayList<ArrayList<Distance>> a, Coordinate ind,ArrayList<Coordinate> classified){
		
		int cnt = 0;
		while (!ind.equals(a.get(cnt).get(0).a)){
			++cnt;
		}
		
		ArrayList<Distance> b = new ArrayList<Distance>(a.get(cnt).size());
		for (int i = 0; i<a.get(cnt).size();++i){
			//System.out.println(String.format("testing %.0f %.0f ind %.0f %.0f %b",a.get(i).a.ii,a.get(i).a.jj,ind.ii,ind.jj,!classified.contains(a.get(i).b)));
			if (!classified.contains(a.get(cnt).get(i).b)){
				b.add(a.get(cnt).get(i));
			}
		}
		Collections.sort(b);
		return b;		
	}
	
		//Get distances for the coordinate of interest. Exclude distances to already classified pixels
	private ArrayList<Distance> getDistances2ndIndex(ArrayList<Distance> a, int ind){
		ArrayList<Distance> b = new ArrayList<Distance>();
		for (int i = 0; i<a.size();++i){
			if (a.get(i).j == ind){
				b.add(a.get(i));
			}
		}
		Collections.sort(b);
		return b;		
	}
	
	public class Distance implements Comparable<Distance>{
		public Coordinate a;
		public Coordinate b;
		public int i;
		public int j;
		public double distance;
		public boolean classified = false;	//Flick this to true once classified
		public Distance(Coordinate a, Coordinate b, int i, int j){
			this.a = new Coordinate(a);
			this.b = new Coordinate(b);
			this.i = i;
			this.j = j;
			Coordinate subst = a.subtract(b);
			distance = Math.sqrt(subst.ii*subst.ii+subst.jj*subst.jj);
		}

		//Implement comparable to enable sorting distances
		@Override
		public int compareTo(Distance a) {
			if (this.distance == a.distance) {
				return 0;	//Return 0 for ties
			} 
			return this.distance < a.distance ? -1 : 1; //Return -1 if this is smaller than a
		}
	}
}