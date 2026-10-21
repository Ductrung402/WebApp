import {createRequire} from 'node:module'
import {resolve,dirname} from 'node:path'
import {fileURLToPath} from 'node:url'
import {randomUUID} from 'node:crypto'
import {writeFile} from 'node:fs/promises'
import assert from 'node:assert/strict'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../../../..'),require=createRequire(resolve(root,'frontend/package.json'))
const {io}=require('socket.io-client');let input='';for await(const chunk of process.stdin)input+=chunk
const config=JSON.parse(input),{apiBase,socketBase,mode}=config
for(const url of [apiBase,socketBase])if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(url))throw Error('Loopback only')
const proof={},sockets=[];let stage='setup'
const pause=ms=>new Promise(r=>setTimeout(r,ms))
const http=async(method,path,body,token)=>{const r=await fetch(apiBase+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});return{status:r.status,data:await r.json()}}
const post=async(path,body,token)=>{const r=await http('POST',path,body,token);assert.ok(r.status<300);return r.data}
const connect=async token=>{const s=io(socketBase,{transports:['websocket'],reconnection:false,auth:token?{token}:{}});sockets.push(s);await new Promise((ok,no)=>{s.once('connect',ok);s.once('connect_error',()=>no(Error('Connect failed')))});return s}
const ack=(s,event,packet)=>s.timeout(8000).emitWithAck(event,packet)
const code=f=>({maCodePhongThi:f.room.maCodePhongThi})
const fixture=async()=>{const email=`submission-${randomUUID()}@example.com`,mat_khau='synthetic-submission-password';await post('/api/auth/register',{email,mat_khau,ho_ten:'Submission teacher'});const {access_token:token}=await post('/api/auth/login',{email,mat_khau});const exam=await post('/api/exams',{tenDeThi:'Submission exam',cauHoiList:['A','B','C'].map((a,i)=>({noiDung:`Question ${i}`,dapAnA:'Option A',dapAnB:'Option B',dapAnC:'Option C',dapAnD:'Option D',dapAnDung:a}))},token);const room=await post('/api/rooms',{idDeThi:exam.idDeThi,tenLop:'Submission class',thoiGianLamBai:10},token);const student=await post('/api/rooms/join',{hoVaTen:'Thí sinh chấm điểm',maCodePhongThi:room.maCodePhongThi});return{token,room,student}}
const join=async(s,f,type)=>{const reply=await ack(s,'join_exam_room',{...code(f),userType:type,idHs:type==='student'?f.student.idHs:null});assert.equal(reply.status,'ok')}
const lookup=s=>`/api/submissions/result?idHs=${s.idHs}&idPhongThi=${s.idPhongThi}`
const noKeys=v=>{if(v&&typeof v==='object'){assert.ok(!Object.hasOwn(v,'dapAnDung'));assert.ok(!Object.hasOwn(v,'dap_an_dung'));for(const c of Object.values(v))noKeys(c)}}
try{
 if(mode==='resume'){const r=await http('GET',lookup(config.student));assert.equal(r.status,200);assert.deepEqual(r.data,config.grade);proof.completedGradeSurvivesTomcatRestart=true}
 else{
  const f=await fixture(),teacher=await connect(f.token),student=await connect();await join(teacher,f,'teacher');await join(student,f,'student')
  assert.equal((await http('GET',lookup(f.student))).status,409)
  assert.equal((await http('GET','/api/submissions/result?idHs=missing&idPhongThi='+f.room.idPhongThi)).status,404)
  assert.equal((await http('GET','/api/submissions/result?idHs=x&idHs=y&idPhongThi=z')).status,422)
  const run=(await ack(teacher,'start_exam',code(f))).exam;assert.ok(run);noKeys(run)
  const body={idHs:f.student.idHs,idPhongThi:f.room.idPhongThi,danhSachCauTraLoi:run.cauHoiList.map((q,i)=>({idCauHoi:q.idCauHoi,dapAnChon:i===0?'A':null}))}
  // SQL order is UUID-based: choose the correct key by matching the synthetic question's content.
  body.danhSachCauTraLoi=run.cauHoiList.map(q=>({idCauHoi:q.idCauHoi,dapAnChon:q.noiDung==='Question 0'?'A':null}))
  let own=[],studentEvents=0,outsideEvents=0;teacher.on('student_submitted',p=>own.push(p));student.on('student_submitted',()=>studentEvents++)
  let otherTeacher
  if(mode==='contracts'){const other=await fixture();otherTeacher=await connect(other.token);await join(otherTeacher,other,'teacher');otherTeacher.on('student_submitted',()=>outsideEvents++)
    assert.equal((await ack(teacher,'submit_exam',{...code(f),runId:run.runId,answers:{},reason:'manual'})).statusCode,403)
    assert.equal((await ack(student,'submit_exam',{...code(f),runId:run.runId,answers:{},idHs:'spoof'})).statusCode,422)
    assert.equal((await http('POST','/api/submissions/submit',{...body,diemSo:10})).status,422)
    assert.equal((await http('POST','/api/submissions/submit',{...body,danhSachCauTraLoi:[{idCauHoi:'foreign',dapAnChon:null}]})).status,422)
  }
  stage='rest-submit';const grade=await post('/api/submissions/submit',body);assert.equal(grade.diemSo,3.33);assert.equal(grade.soCauDung,1);assert.equal(grade.tongSoCau,3);assert.equal(grade.trangThai,'HOAN_THANH');noKeys(grade)
  await pause(100);assert.equal(own.length,1);assert.equal(own[0].diemSo,3.33);assert.equal((await http('GET',lookup(f.student))).status,200)
  const retry=await ack(student,'submit_exam',{...code(f),runId:run.runId,answers:{},reason:'timeout'});assert.equal(retry.status,'ok');assert.deepEqual(retry.result,grade);assert.equal(retry.submission.duplicate,true);assert.equal(retry.submission.reason,'manual');assert.ok(retry.submission.submissionId);assert.ok(Number.isSafeInteger(retry.submission.receivedAt));noKeys(retry)
  if(mode==='contracts'){
    stage='timeout-lost-ack';const second=await post('/api/rooms/join',{hoVaTen:'Timeout student',...code(f)});const s2=await connect();await join(s2,{...f,student:second},'student')
    s2.emit('submit_exam',{...code(f),runId:run.runId,answers:{},reason:'timeout'}) // Deliberately discard the ACK after server commit.
    for(let i=0;i<40;i++){if((await http('GET',lookup(second))).status===200)break;await pause(50)}
    const accepted=await ack(s2,'submit_exam',{...code(f),runId:run.runId,answers:{[run.cauHoiList[0].idCauHoi]:'A'},reason:'manual'});assert.equal(accepted.result.diemSo,0);assert.equal(accepted.submission.reason,'timeout');assert.equal(accepted.submission.duplicate,true)
    await pause(100);assert.equal(studentEvents,0);assert.equal(outsideEvents,0);assert.ok(own.some(p=>p.idHs===second.idHs&&p.diemSo===0));
    Object.assign(proof,{publicRestNoJwt:true,sharedRestSocketGrade:true,exactCamelCaseShape:true,manualAndTimeout:true,retryAndLostAckImmutable:true,teacherOnlyIsolation:true,lookup409404422:true,noAnswerKeys:true})
  }
  if(mode==='close'){
    stage='close';const pending=await post('/api/rooms/join',{hoVaTen:'Chưa nộp',...code(f)}),s3=await connect();await join(s3,{...f,student:pending},'student')
    assert.equal((await ack(s3,'close_exam_room',code(f))).statusCode,403)
    const other=await fixture(),outside=await connect(other.token);await join(outside,other,'teacher');assert.equal((await ack(outside,'close_exam_room',code(f))).statusCode,403);let leaked=0;outside.on('room_closed',()=>leaked++)
    const badRoom=await post('/api/rooms',{idDeThi:run.idDeThi,tenLop:'fail-close-commit',thoiGianLamBai:1},f.token);const badTeacher=await connect(f.token);await join(badTeacher,{...f,room:badRoom},'teacher');let badEvents=0;badTeacher.on('room_closed',()=>badEvents++)
    assert.equal((await ack(badTeacher,'close_exam_room',{maCodePhongThi:badRoom.maCodePhongThi})).statusCode,503);await pause(100);assert.equal(badEvents,0);assert.equal((await http('GET','/api/rooms/'+badRoom.idPhongThi,undefined,f.token)).data.trangThaiPhong,'DANG_CHO')
    const closedEvent=new Promise(r=>s3.once('room_closed',r));const closed=await ack(teacher,'close_exam_room',code(f));assert.equal(closed.status,'ok');assert.equal(closed.trangThaiPhong,'DA_DONG');const event=await closedEvent;assert.deepEqual(Object.keys(event).sort(),['idPhongThi','maCodePhongThi','trangThaiPhong'].sort());assert.equal(event.trangThaiPhong,'DA_DONG');assert.deepEqual(await ack(teacher,'close_exam_room',code(f)),closed)
    assert.equal((await http('POST','/api/rooms/join',{hoVaTen:'Blocked',...code(f)})).status,409);assert.equal((await http('POST','/api/submissions/submit',{idHs:pending.idHs,idPhongThi:pending.idPhongThi,danhSachCauTraLoi:[]})).status,409)
    assert.deepEqual((await http('GET',lookup(f.student))).data,grade);const snapshot=(await http('GET','/api/rooms/'+f.room.idPhongThi,undefined,f.token)).data;assert.equal(snapshot.trangThaiPhong,'DA_DONG');assert.equal(snapshot.ketQuaList.find(s=>s.idHs===pending.idHs).diemSo,null);assert.equal(snapshot.ketQuaList.find(s=>s.idHs===f.student.idHs).diemSo,3.33);assert.equal(leaked,0)
    Object.assign(proof,{ownerOnly:true,retryCloseSafe:true,closeCommitRollbackNoEvent:true,roomIsolation:true,newJoinAndSubmitBlocked:true,completedGradePreserved:true,unfinishedNotMassGraded:true})
  }
  if(mode==='seed')Object.assign(proof,{student:f.student,grade})
 }
 proof.status='PASS'
}catch(error){Object.assign(proof,{status:'FAIL',stage,errorType:error.name,detail:'Submission contract assertion failed'});process.exitCode=1}
finally{for(const socket of sockets)socket.disconnect();await pause(100);proof.clientsClosed=true;await writeFile(resolve(root,`backend/target/verification/${mode==='close'?'T5_3_close':`T5_2_${mode==='contracts'?'contracts':mode==='seed'?'seed':'restart'}`}.json`),JSON.stringify(proof,null,2))}
