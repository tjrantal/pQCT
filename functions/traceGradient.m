%Allow gaps in the contour being traced. Calculate three alternative
%routes, pick the one with the lowest cost. Lowest cost comprises least
%diversion from the initial direction

function [result, iit, jiit] = traceGradient(scaledImage,width,height, result, threshold, i, j)
		global toTrace sobel
		iit =  [];
        jiit = [];
        iit(end+1) = i;
        jiit(end+1) = j;

% 		begin by advancing right. Positive angles rotate the direction clockwise.
	    direction = 0;
		initI = i;
		initJ = j;
		weights = [0.2,0.8,1.0,1,0.8,0.5,0.1];
		directions = [-pi*3 / 4,  -pi*2 / 4, -pi*1 / 4, 0 ,pi*1 / 4, pi*2 / 4, pi*3 / 4];

		values = zeros(length(directions),1);
		bmds = zeros(length(directions),1);
		while (1) 
% 			//Get the values of the pixels in the direction of travel
			toCheck = {						[round(cos(direction+directions(1))),round(sin(direction+directions(1)))], ...	
											[round(cos(direction+directions(2))),round(sin(direction+directions(2)))], ...	
											[round(cos(direction+directions(3))),round(sin(direction+directions(3)))], ...	
											[round(cos(direction+directions(4))),round(sin(direction+directions(4)))], ...	
											[round(cos(direction+directions(5))),round(sin(direction+directions(5)))], ...	
											[round(cos(direction+directions(6))),round(sin(direction+directions(6)))], ...	
											[round(cos(direction+directions(7))),round(sin(direction+directions(7)))] ...	
											};
											
% 			// Look for valid boundary pixel. If one is found, ignore the rest
			routeFound = 0;
			selectInd = 0;
			for t = 1:length(toCheck)
				
				tempI = i +toCheck{t}(1);
                tempJ = j +toCheck{t}(2);
                if  tempI <= width && tempI > 0 && ...
                    tempJ <= height && tempJ > 0
%                     values(t) = toTrace(tempJ,tempI)*weights(t);
                    values(t) = sobel(tempJ,tempI)*weights(t);
                    
                    bmds(t) = scaledImage(tempJ,tempI);
                else
                    values(t) = 0;
                    bmds(t) = 0;
                end
                if t > 1
					if 0 && bmds(t-1) < threshold && bmds(t) >= threshold
% 						//Matching pixel found, keep going along the edge
						selectInd = t;
						routeFound = 1;
						break;
                    end
                end
                
                
            end
			
			if routeFound < 1
                disp('route not found');
                
%                 figure
%                 imshow(scaledImage,[]);
%                 hold on;
%                 plot(j,i,'g+');
%                 keyboard;
% 				IJ.log("ROUTE NOT FOUND???");
                [~, selectInd] =  max(values);
% 				final Vector<Object> returnVector = new Vector<>();
% 				returnVector.add(result);
% 				returnVector.add(iit);
% 				returnVector.add(jiit);
% 				return returnVector;
            end
			direction =direction+ directions(selectInd);
			i = i+ round(cos(direction));
			j = j+ round(sin(direction));
            
%             keyboard;
			if (i == initI && j == initJ) || result(j,i) == 1 || result(j,i) > 3
%                 figure
%                 imshow(result,[]);
%                 keyboard;
				result(result > 1) = 1;
				break;
			else 
				if (result(j,i) == 0) 
					result(j,i) = 2;
                end
                if (result(j,i) ~= 1) 
					result(j,i) = result(j,i)+1;
                end
				iit(end+1) =i;
				jiit(end+1) = j;

            end
        end
end