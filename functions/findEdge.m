function [edges, result] =  findEdge(scaledImage, width, height, threshold)
	edges =struct();
	i = 1;
	j = 1;
    global toTrace sobel
    temp = scaledImage';
		sobel = sc.fiji.pQCT.io.ScaledImageData.sobel(temp(:),width,height);	%Get the gradient image for tracing
		sobel = reshape(sobel,[width,height])';
		toTrace = scaledImage.*sobel;
    
	result = zeros(height,width);
	%Scan through the whole image loop
	while i < width && j < height-1
		%Look for the next starting point loop
		while i < width && j < height && (scaledImage(j,i) < threshold || result(j,i) > 0)
			i = i+1;
			if i == width
				j = j+1;
				if j >= height 
					break;
				end
				i = 1;
			end
		end
       
		if  i >= width  && j >= height
			break; %All done
        end
        disp(sprintf('Init %d %d',j,i));
		result(j,i) = 1;

        [result, iit, jiit] = traceGradient(scaledImage,width,height, result, threshold, i, j);
        result = imfill(result,'holes');    %Fill the traced outline
        result = imdilate(result,[0,1,0;1,1,1;0,1,0]);
        result = imdilate(result,[0,1,0;1,1,1;0,1,0]);
        result = imdilate(result,[0,1,0;1,1,1;0,1,0]);
        if ~isfield(edges,'iit')
            edges(1).iit = iit;
        else
            edges(end+1).iit = iit;
        end
        edges(end).jiit = jiit;
        
        figure('position',[800,120,500,500])
        imshow(sobel,[]);
        hold on;
        plot(i,j,'ro');
        hold on;
        plot(iit,jiit,'b');
%          
%          figure
%         imshow(result,[]);

        keyboard;
	end

end