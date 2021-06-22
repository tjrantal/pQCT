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

public class ClusterPoints{
	public ArrayList<Coordinate> cluster1;
	public ArrayList<Coordinate> cluster2;
	
	public ClusterPoints(ArrayList<Coordinate> coordinates){
		//Calculate distances between coordinates
		ArrayList<Distance> distances = new ArrayList<Distance>(coordinates.size()*coordinates.size());
		for (int i =0; i<distances.size()-1;++i){
			for (int j =i+1; j<distances.size();++j){
				distances.add(new Distance(coordinates.get(i),coordinates.get(j),i,j));
			}
		}
		Collections.sort(distances); 	//Sort distances here
		//Start going through the dataset with the farthest apart points as the cluster centres
		cluster1 = new ArrayList<Coordinate>(coordinates.size());
		cluster2 = new ArrayList<Coordinate>(coordinates.size());
		cluster1.add(new Coordinate(coordinates.get(distances.get(distances.size()-1).i)));
		cluster2.add(new Coordinate(coordinates.get(distances.get(distances.size()-1).j)));
		
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