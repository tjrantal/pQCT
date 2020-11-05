close all;
fclose all;
clear all;
clc;

javaclasspath({'.','target/pQCT_-3.0.0-SNAPSHOT.jar'});
addpath('functions');
% javaclasspath({'.','./target/classes'});
stm = sc.fiji.pQCT.StratecToMatlab('I0020940.M01');
scaling = stm.getScaleCoefficients();

wh = stm.getSize();
pixels = double(stm.getPixels()-int32(2^15))*scaling(2)/1000+scaling(1);
image = reshape(pixels,[wh(2), wh(1)])';
filtered = medfilt2(image,[3 3]);


[result mask] = findEdge(filtered,wh(1),wh(2),200);

figure
ah = [];
subplot(1,2,1);
imshow(filtered,[]);
ah(1)  = gca();
subplot(1,2,2);
imshow(mask,[]);
ah(2)  = gca();
linkaxes(ah,'xy');