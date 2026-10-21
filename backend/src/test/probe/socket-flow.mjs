import {createRequire} from 'node:module'
import {resolve,dirname} from 'node:path'
import {fileURLToPath} from 'node:url'
import {randomUUID} from 'node:crypto'
import {writeFile} from 'node:fs/promises'
import assert from 'node:assert/strict'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../../../..'),require=createRequire(resolve(root,'frontend/package.json'))
const {io}=require('socket.io-client');let input='';for await(const c of process.stdin)input+=c
const config=JSON.parse(input),{apiBase,socketBase,mode}=config
for(const url of [apiBase,socketBase])if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(url))throw Error('Loopback only')
const output={flow:'T4_2_socket', 'seed-restart':'T4_2_seed',resume:'T4_2_restart',missing:'T4_2_missing'}[mode]
const sockets=[],proof={client:'installed socket.io-client 4.x; WebSocket transport',backend:'Tomcat + Netty 2.0.13 + isolated PostgreSQL'};let stage='setup'
const pause=ms=>new Promise(r=>setTimeout(r,ms))
const req=async(path,body,token)=>{const r=await fetch(apiBase+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});assert.ok(r.ok);return r.json()}
const account=async()=>{const email=`socket-${randomUUID()}@example.com`,mat_khau='synthetic-socket-password';await req('/api/auth/register',{email,mat_khau,ho_ten:'Socket teacher'});return(await req('/api/auth/login',{email,mat_khau})).access_token}
const fixture=async()=>{const token=await account();const exam=await req('/api/exams',{tenDeThi:'Đề trực tiếp',cauHoiList:[{noiDung:'Nước?',dapAnA:'H2O',dapAnB:'CO2',dapAnC:'O2',dapAnD:'N2',dapAnDung:'A'}]},token);const room=await req('/api/rooms',{idDeThi:exam.idDeThi,tenLop:'Lớp Socket',thoiGianLamBai:10},token);const student=await req('/api/rooms/join',{hoVaTen:'Socket student',maCodePhongThi:room.maCodePhongThi});return{token,examId:exam.idDeThi,room,student}}
const connect=async(token,extras={})=>{const s=io(socketBase,{autoConnect:false,transports:['websocket'],reconnection:false,auth:{...extras,...(token?{token}:{})}});sockets.push(s);await new Promise((ok,no)=>{const t=setTimeout(()=>no(Error('connect timeout')),8000);s.once('connect',()=>{clearTimeout(t);ok()});s.once('connect_error',()=>{clearTimeout(t);no(Error('connect error'))});s.connect()});return s}
const ack=(s,event,data)=>s.timeout(8000).emitWithAck(event,data)
const join=(s,f,role='student')=>ack(s,'join_exam_room',{maCodePhongThi:f.room.maCodePhongThi,userType:role,idHs:role==='student'?f.student.idHs:null})
const code=f=>({maCodePhongThi:f.room.maCodePhongThi})
const noKeys=value=>{if(value&&typeof value==='object'){assert.ok(!Object.hasOwn(value,'dapAnDung'));assert.ok(!Object.hasOwn(value,'dap_an_dung'));for(const v of Object.values(value))noKeys(v)}}
try{
 if(mode==='resume'||mode==='missing'){
   stage='restart';const s=await connect();assert.equal((await join(s,config)).status,'ok');const reply=await ack(s,'sync_exam',code(config));
   if(mode==='missing'){assert.equal(reply.statusCode,503);proof.missingTimingFailsClosed=true}
   else{assert.equal(reply.status,'ok');assert.equal(reply.exam.runId,config.run.runId);assert.equal(reply.exam.endsAt,config.run.endsAt);assert.ok(reply.exam.serverTime>config.run.serverTime);noKeys(reply);proof.originalDeadlineRestored=true}
 }else{
  const f=await fixture(),teacher=await connect(f.token),s=await connect()
  if(mode==='seed-restart'){
   assert.equal((await join(teacher,f,'teacher')).status,'ok');assert.equal((await join(s,f)).status,'ok');const started=await ack(teacher,'start_exam',code(f));assert.equal(started.status,'ok');Object.assign(proof,{room:f.room,student:f.student,run:started.exam});noKeys(started)
  }else{
   stage='auth-validation';let rejected=false;try{await connect('invalid-token')}catch{rejected=true}assert.ok(rejected)
   rejected=false;try{await connect(null,{unexpected:true})}catch{rejected=true}assert.ok(rejected)
   const invalid=await ack(s,'join_exam_room',{});assert.equal(invalid.statusCode,422);assert.equal(invalid.detail,'Invalid room join payload.');assert.equal((await ack(s,'sync_exam',{})).detail,'Invalid synchronization payload.');assert.equal((await ack(s,'start_exam',{})).detail,'Invalid start payload.');assert.equal((await ack(s,'reconnect_exam',{})).detail,'Invalid reconnect payload.');assert.equal((await ack(s,'sync_exam',code(f))).statusCode,403);assert.equal((await join(s,f,'teacher')).statusCode,401)
   const other=await fixture(),otherTeacher=await connect(other.token),outside=await connect();assert.equal((await join(otherTeacher,f,'teacher')).statusCode,403);assert.equal((await join(otherTeacher,other,'teacher')).status,'ok');assert.equal((await join(outside,{room:other.room,student:f.student})).statusCode,403)
   stage='memberships';const rosters=[];let studentPrivate=0,otherPrivate=0,otherExam=0;s.on('room_students_updated',()=>studentPrivate++);otherTeacher.on('room_students_updated',p=>{if(p.maCodePhongThi===f.room.maCodePhongThi)otherPrivate++});otherTeacher.on('exam_started',()=>otherExam++)
   teacher.on('room_students_updated',p=>rosters.push(p));const joined=await join(teacher,f,'teacher');assert.equal(joined.status,'ok');assert.equal(joined.idHs,null)
   assert.equal((await join(s,f)).status,'ok');const second=await connect();assert.equal((await join(second,f)).status,'ok');await pause(150);assert.equal(rosters.at(-1).hocSinhList.length,1)
   s.disconnect();await pause(150);assert.equal(rosters.at(-1).hocSinhList.length,1);assert.equal(studentPrivate,0);assert.equal(otherPrivate,0)
   stage='start';assert.equal((await ack(second,'start_exam',code(f))).statusCode,403)
   const received=new Promise(r=>second.once('exam_started',r));const start=await ack(teacher,'start_exam',code(f));assert.equal(start.status,'ok');assert.equal(start.exam.trangThaiPhong,'DANG_THI');const event=await received;assert.equal(event.runId,start.exam.runId);noKeys(start);noKeys(event)
   const retry=await ack(teacher,'start_exam',code(f));assert.equal(retry.exam.runId,start.exam.runId);assert.equal(retry.exam.endsAt,start.exam.endsAt);assert.equal(otherExam,0)
   const sync=await ack(second,'sync_exam',code(f));assert.equal(sync.status,'ok');assert.equal(sync.exam.endsAt,start.exam.endsAt);assert.equal(sync.submission,null);assert.equal(sync.studentState.idHs,f.student.idHs);assert.ok(Number.isSafeInteger(sync.requestReceivedAt));assert.ok(sync.serverTime>=sync.requestReceivedAt)
   const recover=await ack(second,'reconnect_exam',{...code(f),idHs:f.student.idHs});assert.equal(recover.status,'ok');assert.equal(recover.studentState.trangThai,'DANG_THI');assert.equal(recover.exam.endsAt,start.exam.endsAt);assert.ok(recover.remainingSeconds<=600)
   assert.equal((await ack(second,'reconnect_exam',{...code(f),idHs:other.student.idHs})).statusCode,403);assert.equal((await ack(second,'sync_exam',code(other))).statusCode,403)
   assert.equal((await ack(second,'submit_exam',{})).statusCode,422);assert.equal((await ack(teacher,'close_exam_room',{})).statusCode,422)
   stage='start-commit-failure';const failing={room:await req('/api/rooms',{idDeThi:f.examId,tenLop:'fail-start-commit',thoiGianLamBai:10},f.token)};const failTeacher=await connect(f.token);assert.equal((await join(failTeacher,failing,'teacher')).status,'ok');let failedBroadcast=0;failTeacher.on('exam_started',()=>failedBroadcast++);assert.equal((await ack(failTeacher,'start_exam',code(failing))).statusCode,503);await pause(100);assert.equal(failedBroadcast,0);const failedRoom=await(await fetch(`${apiBase}/api/rooms/${failing.room.idPhongThi}`,{headers:{Authorization:`Bearer ${f.token}`}})).json();assert.equal(failedRoom.trangThaiPhong,'DANG_CHO')
   await writeFile(resolve(root,'backend/target/verification/T4_2_started.json'),JSON.stringify(event,null,2))
   Object.assign(proof,{ownership:true,teacherAuth:true,privateRoster:true,roomIsolation:true,multiSocketDedup:true,startState:'DANG_THI',immutableDeadline:true,publicWhitelist:true,clockSync:true,invalidSubmitAndClose422:true,failedCommitNoStartOrBroadcast:true})
  }
 }
 proof.status='PASS'
}catch(e){Object.assign(proof,{status:'FAIL',stage,errorType:e.name,detail:'Socket assertions failed; no private values recorded'});process.exitCode=1}
finally{for(const s of sockets)s.disconnect();await pause(100);proof.clientsClosed=true;await writeFile(resolve(root,`backend/target/verification/${output}.json`),JSON.stringify(proof,null,2))}
