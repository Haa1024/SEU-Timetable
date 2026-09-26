// Supplement a dense timetable with overlapping vertical views. Each view keeps
// the weekday header and the full column height, so the model can align late
// classes with their header instead of guessing from wrapped course names.
export async function timetableImageViews(messages) {
  const latest=messages.findLast(m=>m.role==='user');
  const images=Array.isArray(latest?.content)?latest.content.filter(p=>p.type==='image_url'):[];
  const views=[];
  for(const [i,part] of images.entries()) {
    const image=new Image();image.src=part.image_url.url;await image.decode();
    const bitmap=await createImageBitmap(image);
    try {
      const content=[{type:'text',text:`原图 ${i+1} 的左、中、右重叠竖条，均保留原星期表头及全高。仅用于核对所在列，不是额外课程。边缘被裁切的课程应与原图/相邻竖条合并读取。`}];
      for(const fraction of [0,.275,.55]) {
        const canvas=document.createElement('canvas'),x=Math.round(bitmap.width*fraction);
        canvas.width=Math.min(Math.ceil(bitmap.width*.45),bitmap.width-x);canvas.height=bitmap.height;
        canvas.getContext('2d').drawImage(bitmap,x,0,canvas.width,bitmap.height,0,0,canvas.width,bitmap.height);
        const limit=6*1024*1024/Math.max(1,images.length)/3;
        let url=canvas.toDataURL('image/jpeg',.9);
        for(const quality of [.75,.6,.45]){if(url.length<=limit)break;url=canvas.toDataURL('image/jpeg',quality);}
        if(url.length>limit)throw new Error('课表局部图过大，请把照片分批上传');
        content.push({type:'image_url',image_url:{url}});
      }
      views.push({role:'user',content});
    } finally {bitmap.close();}
  }
  return views;
}
